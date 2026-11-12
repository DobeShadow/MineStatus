package com.dobeshadow.minestatus.config;

import com.dobeshadow.minestatus.MineStatusPlugin;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * 配置读取与版本校验。
 * <p>
 * 所有配置项集中在这里读取，避免散落在各处。
 * 当 config.yml 的 config-version 与代码内期望值不一致时，
 * 会在 OP 进服时提示其更新配置文件。
 */
public class ConfigManager {

    /** 代码期望的配置版本，config.yml 结构变更时必须同步 +1 */
    public static final int EXPECTED_CONFIG_VERSION = 1;

    private final MineStatusPlugin plugin;

    private boolean enabled;
    private int intervalSeconds;
    private String endpoint;
    private String token;
    private int maxPlayersInPayload;
    private int headCacheMaxPlayers;
    private int skinCacheMinutes;
    private int skinDownloadTimeoutSeconds;
    private int pushTimeoutSeconds;
    private boolean skipTlsVerify;
    private boolean debug;

    public ConfigManager(MineStatusPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 从磁盘重新加载配置并刷新内存字段。
     */
    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration cfg = plugin.getConfig();

        this.enabled = cfg.getBoolean("enabled", true);
        this.intervalSeconds = Math.max(1, cfg.getInt("interval-seconds", 5));
        this.endpoint = cfg.getString("endpoint", "");
        this.token = cfg.getString("token", "");
        this.maxPlayersInPayload = Math.max(1, cfg.getInt("max-players-in-payload", 100));
        this.headCacheMaxPlayers = Math.max(1, cfg.getInt("head-cache-max-players", 500));
        this.skinCacheMinutes = Math.max(0, cfg.getInt("skin-cache-minutes", 60));
        this.skinDownloadTimeoutSeconds = Math.max(1, cfg.getInt("skin-download-timeout-seconds", 5));
        this.pushTimeoutSeconds = Math.max(1, cfg.getInt("push-timeout-seconds", 5));
        this.skipTlsVerify = cfg.getBoolean("skip-tls-verify", false);
        this.debug = cfg.getBoolean("debug", false);
    }

    /**
     * 判断当前磁盘上的配置版本是否落后于代码期望版本。
     *
     * @return true 表示存在旧配置，需要提示用户更新
     */
    public boolean isConfigOutdated() {
        int current = plugin.getConfig().getInt("config-version", 0);
        return current != EXPECTED_CONFIG_VERSION;
    }

    /**
     * 检查 endpoint / token 是否已正确填写。
     */
    public boolean isEndpointConfigured() {
        return endpoint != null && !endpoint.isBlank()
                && token != null && !token.isBlank()
                && !token.startsWith("CHANGE_ME");
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getIntervalSeconds() {
        return intervalSeconds;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getToken() {
        return token;
    }

    public int getMaxPlayersInPayload() {
        return maxPlayersInPayload;
    }

    /** 历史头像库最多保留多少名玩家，超出按最近更新时间淘汰最老的 */
    public int getHeadCacheMaxPlayers() {
        return headCacheMaxPlayers;
    }

    public int getSkinCacheMinutes() {
        return skinCacheMinutes;
    }

    public int getSkinDownloadTimeoutSeconds() {
        return skinDownloadTimeoutSeconds;
    }

    public int getPushTimeoutSeconds() {
        return pushTimeoutSeconds;
    }

    public boolean isSkipTlsVerify() {
        return skipTlsVerify;
    }

    public boolean isDebug() {
        return debug;
    }
}
