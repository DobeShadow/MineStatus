package com.dobeshadow.minestatus;

import com.dobeshadow.minestatus.collect.PlayerSnapshotService;
import com.dobeshadow.minestatus.collect.PlayerSnapshotService.PlayerInfo;
import com.dobeshadow.minestatus.command.MineStatusCommand;
import com.dobeshadow.minestatus.config.ConfigManager;
import com.dobeshadow.minestatus.push.StatusPusher;
import com.dobeshadow.minestatus.skin.HeadCache;
import com.dobeshadow.minestatus.skin.SkinService;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * MineStatus 主类。
 * <p>
 * 职责：生命周期管理、模块装配、定时调度。
 * 具体逻辑分散在 config / collect / skin / push / command 各包中。
 */
public class MineStatusPlugin extends JavaPlugin implements Listener {

    private ConfigManager configManager;
    private PlayerSnapshotService snapshotService;
    private SkinService skinService;
    private StatusPusher statusPusher;
    /** 跨重启持久化的玩家头像历史（老玩家离线后榜单仍能显示真实皮肤） */
    private HeadCache headCache;

    /** 当前定时任务句柄，reload 时需要取消重建 */
    private BukkitTask pushTask;

    /** 防止上一次推送未完成时重复触发，避免网络慢时任务堆积 */
    private final AtomicBoolean pushing = new AtomicBoolean(false);

    /** 头像并行下载线程池（守护线程，插件卸载时关闭） */
    private ExecutorService skinExecutor;

    @Override
    public void onEnable() {
        // 0. 头像下载线程池（守护线程，避免阻塞服务端关闭）
        this.skinExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "MineStatus-Skin");
            t.setDaemon(true);
            return t;
        });

        // 1. 配置
        this.configManager = new ConfigManager(this);
        this.configManager.load();

        // 2. 模块装配
        this.snapshotService = new PlayerSnapshotService(this, configManager);
        this.skinService = new SkinService(this, configManager);
        this.statusPusher = new StatusPusher(this, configManager);
        this.headCache = new HeadCache(getDataFolder().toPath(), getLogger(),
                configManager.isDebug(), configManager.getHeadCacheMaxPlayers());

        // 3. 命令
        MineStatusCommand command = new MineStatusCommand(this);
        var cmd = getCommand("minestatus");
        if (cmd != null) {
            cmd.setExecutor(command);
            cmd.setTabCompleter(command);
        }

        // 4. 事件监听（OP 进服提示旧配置）
        Bukkit.getPluginManager().registerEvents(this, this);

        // 5. 启动定时任务
        startPushTask();

        // 6. 启动提示
        getLogger().info("MineStatus 已启用，推送间隔 " + configManager.getIntervalSeconds() + " 秒。");
        if (!configManager.isEndpointConfigured()) {
            getLogger().warning("endpoint 或 token 尚未正确配置，自动推送将被跳过。请编辑 plugins/MineStatus/config.yml。");
        }
        if (configManager.isConfigOutdated()) {
            getLogger().warning("检测到旧版 config.yml（config-version 不匹配），请备份后删除并重新生成。");
        }
    }

    @Override
    public void onDisable() {
        if (pushTask != null) {
            pushTask.cancel();
            pushTask = null;
        }
        if (skinExecutor != null) {
            skinExecutor.shutdownNow();
            skinExecutor = null;
        }
        if (headCache != null) {
            headCache.saveIfNeeded();
        }
        getLogger().info("MineStatus 已禁用。");
    }

    /**
     * 启动（或重启）定时推送任务。
     */
    private void startPushTask() {
        if (pushTask != null) {
            pushTask.cancel();
            pushTask = null;
        }
        if (!configManager.isEnabled()) {
            getLogger().info("自动推送已关闭（enabled: false）。");
            return;
        }
        long intervalTicks = configManager.getIntervalSeconds() * 20L;
        pushTask = Bukkit.getScheduler().runTaskTimer(this, this::triggerPush, intervalTicks, intervalTicks);
    }

    /**
     * 触发一次推送（主线程调用）。
     * <p>
     * 流程：主线程快照 → 异步补头像 → 异步 POST。
     */
    private void triggerPush() {
        if (!pushing.compareAndSet(false, true)) {
            if (configManager.isDebug()) {
                getLogger().info("上一次推送尚未完成，跳过本次。");
            }
            return;
        }

        // 主线程快照
        List<PlayerInfo> players = snapshotService.snapshot();
        Map<String, Object> root = snapshotService.buildRoot(players);

        // 异步补头像 + 推送
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                fillHeads(players, root);
                headCache.saveIfNeeded();

                // 有新采集到的头像才附带 heads 字段；PHP 端是合并语义，
                // 日常推送不会拖着几百 KB 的历史库反复上传
                boolean withHeads = headCache.isDirty();
                long pushedVersion = headCache.currentVersion();
                if (withHeads) {
                    root.put("heads", headCache.snapshot());
                }

                // 等待推送真正完成后再释放锁，避免网络慢时任务堆积
                StatusPusher.PushResult result = statusPusher.pushAsync(root).join();
                if (withHeads && result.success()) {
                    headCache.markPushed(pushedVersion);
                }
            } catch (Throwable t) {
                if (configManager.isDebug()) {
                    getLogger().warning("定时推送异常: " + t.getMessage());
                }
            } finally {
                pushing.set(false);
            }
        });
    }

    /**
     * 异步为每个玩家补全头像。
     * <p>
     * 使用主线程快照时持有的 Player 引用读取 profile，
     * 若玩家在采集后离线则跳过（head 保持空字符串）。
     * <p>
     * 采用并行下载（固定线程池），避免串行下载导致单次推送耗时过长。
     */
    private void fillHeads(List<PlayerInfo> players, Map<String, Object> root) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> playerList = (List<Map<String, Object>>) root.get("players");
        if (playerList == null || playerList.isEmpty()) {
            return;
        }

        int count = Math.min(players.size(), playerList.size());
        List<CompletableFuture<Void>> futures = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            PlayerInfo info = players.get(i);
            Map<String, Object> entry = playerList.get(i);
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    Player player = info.player();
                    if (player == null || !player.isOnline()) {
                        return;
                    }
                    // 只接受真实采集到的皮肤；采集失败留空，由 PHP 用默认头替换，
                    // 绝不把兜底 Steve 或异常立绘写进历史缓存
                    String head = skinService.getRealHeadDataUri(player);
                    if (head != null && !head.isEmpty()) {
                        entry.put("head", head);
                        headCache.put(info.name(), head);
                    }
                } catch (Throwable t) {
                    if (configManager.isDebug()) {
                        getLogger().warning("补全头像失败: " + info.name() + " - " + t.getMessage());
                    }
                }
            }, skinExecutor));
        }

        // 等待全部完成（每个任务内部已有超时，不会无限等待）
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } catch (Throwable t) {
            if (configManager.isDebug()) {
                getLogger().warning("等待头像补全时异常: " + t.getMessage());
            }
        }
    }

    /**
     * 立即推送一次（供命令调用），完成后回调结果。
     * <p>
     * 回调在主线程执行，方便直接给玩家发消息。
     */
    public void pushNow(Consumer<StatusPusher.PushResult> callback) {
        if (!pushing.compareAndSet(false, true)) {
            Bukkit.getScheduler().runTask(this, () ->
                    callback.accept(StatusPusher.PushResult.failed("上一次推送尚未完成")));
            return;
        }

        List<PlayerInfo> players = snapshotService.snapshot();
        Map<String, Object> root = snapshotService.buildRoot(players);

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            StatusPusher.PushResult result;
            try {
                fillHeads(players, root);
                headCache.saveIfNeeded();
                boolean withHeads = headCache.isDirty();
                long pushedVersion = headCache.currentVersion();
                if (withHeads) {
                    root.put("heads", headCache.snapshot());
                }
                result = statusPusher.pushAsync(root).join();
                if (withHeads && result.success()) {
                    headCache.markPushed(pushedVersion);
                }
            } catch (Throwable t) {
                result = StatusPusher.PushResult.failed(t.getClass().getSimpleName() + ": " + t.getMessage());
            } finally {
                pushing.set(false);
            }
            StatusPusher.PushResult finalResult = result;
            Bukkit.getScheduler().runTask(this, () -> callback.accept(finalResult));
        });
    }

    /**
     * 重载配置并重置定时任务。
     */
    public void reloadPluginConfig() {
        configManager.load();
        skinService.clearCache();
        // 关键：按新配置重建推送客户端，否则 skip-tls-verify / 超时等修改不会生效
        statusPusher.reloadClient();
        startPushTask();
    }

    /**
     * OP 进服时提示旧配置。
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.isOp()) {
            return;
        }
        if (configManager.isConfigOutdated()) {
            player.sendMessage("§e[MineStatus] §c检测到旧版配置文件，请备份后删除 plugins/MineStatus/config.yml 并重启以生成新配置。");
        }
        if (!configManager.isEndpointConfigured()) {
            player.sendMessage("§e[MineStatus] §cendpoint 或 token 尚未配置，自动推送已跳过。");
        }
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public SkinService getSkinService() {
        return skinService;
    }

    public HeadCache getHeadCache() {
        return headCache;
    }

    public StatusPusher getStatusPusher() {
        return statusPusher;
    }
}
