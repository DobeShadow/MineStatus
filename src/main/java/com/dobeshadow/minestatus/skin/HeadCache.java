package com.dobeshadow.minestatus.skin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 玩家头像历史缓存（跨重启持久化）。
 * <p>
 * 背景：MINEMC 是 offline/cracked 服，玩家 UUID 全是 v3 离线 UUID，
 * Mojang / minotar / crafatar 都查不到皮肤，网页侧只能显示默认 Steve。
 * 唯一能拿到真实皮肤的时机是玩家在线时从服务端 PlayerProfile 读取。
 * <p>
 * 因此这里把每次成功采集到的真实头像按玩家名落盘到
 * {@code plugins/MineStatus/heads.json}，随状态推送一起发给 PHP，
 * 由网页把离线玩家的默认 Steve 替换成历史真实头像。
 * 玩家陆续上线后，榜单里的 Steve 会越来越少。
 * <p>
 * 只存「真实采集到的」8x8 像素头像；兜底头、异常立绘一律不入库。
 * 文件格式固定为 {@code {"version":1,"heads":{"name":"data:image/png;base64,..."}}}，
 * 键名与值都不含引号/反斜杠，读写用手写 JSON，不引入第三方库。
 */
public final class HeadCache {

    /** 单个头像 data URI 上限，与网页侧三个 ingest 的判据保持一致 */
    public static final int MAX_HEAD_URI_LENGTH = 1600;

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{1,32}$");
    private static final Pattern ENTRY = Pattern.compile(
            "\"([A-Za-z0-9_]{1,32})\"\\s*:\\s*\"(data:image/png;base64,[A-Za-z0-9+/=]+)\"");
    private static final String PREFIX = "data:image/png;base64,";

    private final Path file;
    private final Logger logger;
    private final int maxEntries;
    private final boolean debug;

    /** accessOrder=true：最近更新的排在末尾，裁剪时从最老的删起 */
    private final LinkedHashMap<String, String> heads = new LinkedHashMap<>(64, 0.75f, true);

    /** 是否有尚未成功推送到网页的变化 */
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    /** 是否有尚未落盘的变化 */
    private final AtomicBoolean needsSave = new AtomicBoolean(false);
    /** 每次 put 递增，用于判断推送期间有没有新变化，避免误清 dirty */
    private long version = 0L;

    public HeadCache(Path dataFolder, Logger logger, boolean debug, int maxEntries) {
        this.file = dataFolder.resolve("heads.json");
        this.logger = logger;
        this.debug = debug;
        this.maxEntries = Math.max(1, maxEntries);
        load();
    }

    /** 从磁盘恢复历史缓存。文件不存在 / 损坏都按空缓存处理，不影响插件启动。 */
    private void load() {
        String raw;
        try {
            if (!Files.isRegularFile(file)) {
                return;
            }
            raw = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.warning("[MineStatus] 读取头像缓存失败，将按空缓存继续: " + e.getMessage());
            return;
        }
        Matcher m = ENTRY.matcher(raw);
        int loaded = 0;
        synchronized (this) {
            while (m.find()) {
                String name = m.group(1);
                String head = m.group(2);
                if (head.length() > MAX_HEAD_URI_LENGTH || !NAME.matcher(name).matches()) {
                    continue;
                }
                heads.put(name, head);
                loaded++;
            }
            trim();
        }
        if (loaded > 0) {
            logger.info("[MineStatus] 已恢复 " + loaded + " 名玩家的头像缓存。");
        }
    }

    /**
     * 记录一名玩家的真实头像。
     * <p>
     * 只接受长度合理的 PNG data URI；兜底头 / 异常立绘会被调用方过滤，
     * 这里再做一次格式与体积校验作为双保险。
     *
     * @return true 表示缓存内容发生了变化
     */
    public boolean put(String name, String head) {
        if (name == null || !NAME.matcher(name).matches()) {
            return false;
        }
        if (head == null || head.length() > MAX_HEAD_URI_LENGTH || !head.startsWith(PREFIX)) {
            return false;
        }
        synchronized (this) {
            if (head.equals(heads.get(name))) {
                return false;
            }
            heads.put(name, head);
            trim();
            version++;
        }
        dirty.set(true);
        needsSave.set(true);
        if (debug) {
            logger.info("[MineStatus] 记录头像: " + name + " (" + head.length() + " 字符)");
        }
        return true;
    }

    /** 返回当前全量缓存的副本，供推送使用。 */
    public synchronized Map<String, String> snapshot() {
        return new LinkedHashMap<>(heads);
    }

    public synchronized int size() {
        return heads.size();
    }

    public boolean isDirty() {
        return dirty.get();
    }

    public synchronized long currentVersion() {
        return version;
    }

    /**
     * 标记某个版本的缓存已成功推送。
     * 若推送期间又有新玩家被记录（version 变了），保留 dirty 下次继续推。
     */
    public synchronized void markPushed(long pushedVersion) {
        if (pushedVersion == version) {
            dirty.set(false);
        }
    }

    /** 有变化时才原子落盘，避免每轮推送都写文件。IO 在锁外执行。 */
    public void saveIfNeeded() {
        Map<String, String> copy;
        synchronized (this) {
            if (!needsSave.get()) {
                return;
            }
            copy = new LinkedHashMap<>(heads);
            needsSave.set(false);
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, toJson(copy), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            synchronized (this) {
                needsSave.set(true);
            }
            logger.warning("[MineStatus] 保存头像缓存失败，将在下次推送重试: " + e.getMessage());
        }
    }

    private synchronized void trim() {
        while (heads.size() > maxEntries) {
            String oldest = heads.keySet().iterator().next();
            heads.remove(oldest);
        }
    }

    private static String toJson(Map<String, String> map) {
        StringBuilder sb = new StringBuilder(256 + map.size() * 900);
        sb.append("{\"version\":1,\"heads\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":\"").append(e.getValue()).append('"');
        }
        sb.append("}}");
        return sb.toString();
    }
}
