package com.dobeshadow.minestatus.collect;

import com.dobeshadow.minestatus.MineStatusPlugin;
import com.dobeshadow.minestatus.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 在线玩家快照服务。
 * <p>
 * 线程约束：{@link #snapshot()} 必须在主线程调用（读取 Bukkit.getOnlinePlayers()）。
 * 返回的是纯数据对象，后续头像补全与序列化都在异步线程完成。
 */
public class PlayerSnapshotService {

    private final ConfigManager config;

    public PlayerSnapshotService(MineStatusPlugin plugin, ConfigManager config) {
        this.config = config;
    }

    /**
     * 在主线程采集在线玩家基础信息（名字 + UUID + Player 引用）。
     * <p>
     * 注意：不在此处获取头像，避免网络 IO 阻塞主线程。
     * 同时持有 Player 引用，供异步线程读取 profile（只读操作，Paper 下线程安全）。
     *
     * @return 玩家基础信息列表，已按 max-players-in-payload 截断
     */
    public List<PlayerInfo> snapshot() {
        List<PlayerInfo> list = new ArrayList<>();
        int limit = config.getMaxPlayersInPayload();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (list.size() >= limit) {
                break;
            }
            list.add(new PlayerInfo(player.getName(), player.getUniqueId(), player));
        }
        return list;
    }

    /**
     * 构建完整的推送数据根对象（不含头像，头像由调用方异步补全）。
     * <p>
     * 必须在主线程调用。
     */
    public Map<String, Object> buildRoot(List<PlayerInfo> players) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("online", true);
        root.put("time", System.currentTimeMillis() / 1000);
        root.put("max", Bukkit.getMaxPlayers());
        root.put("version", Bukkit.getMinecraftVersion());

        List<Map<String, Object>> playerList = new ArrayList<>();
        for (PlayerInfo info : players) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", info.name());
            m.put("uuid", info.uuid().toString());
            m.put("head", "");
            playerList.add(m);
        }
        root.put("players", playerList);
        return root;
    }

    /**
     * 玩家基础信息（不可变）。
     * <p>
     * player 引用用于异步线程读取 profile，使用前务必检查 isOnline()。
     */
    public record PlayerInfo(String name, UUID uuid, Player player) {
    }
}
