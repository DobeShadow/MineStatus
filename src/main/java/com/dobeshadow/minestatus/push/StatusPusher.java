package com.dobeshadow.minestatus.push;

import com.dobeshadow.minestatus.MineStatusPlugin;
import com.dobeshadow.minestatus.config.ConfigManager;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 状态推送器。
 * <p>
 * 负责把组装好的数据序列化为 JSON 并异步 POST 到 PHP 接口。
 * 所有网络操作均在异步线程，失败仅记录 debug 日志，不刷屏。
 * <p>
 * 说明：JSON 采用手写序列化，不依赖任何第三方库，
 * 因为数据结构固定且简单（几个标量 + 玩家数组），手写更可控。
 */
public class StatusPusher {

    private final MineStatusPlugin plugin;
    private final ConfigManager config;
    private final HttpClient http;

    /** 上一次推送结果，供 /minestatus status 查询 */
    private volatile PushResult lastResult = PushResult.never();

    public StatusPusher(MineStatusPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
        this.http = config.isSkipTlsVerify()
                ? buildInsecureClient(config.getPushTimeoutSeconds())
                : HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(config.getPushTimeoutSeconds()))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
        if (config.isSkipTlsVerify()) {
            plugin.getLogger().warning("已启用 skip-tls-verify：将跳过 HTTPS 证书校验，请仅在证书异常时临时使用。");
        }
    }

    /**
     * 构建跳过 TLS 证书校验的客户端（信任所有证书 + 关闭主机名校验）。
     * <p>
     * 仅用于目标站点证书异常（过期 / 域名不匹配）且暂时无法修复的场景。
     * 存在中间人攻击的理论风险，证书修好后请把 skip-tls-verify 改回 false。
     */
    private static HttpClient buildInsecureClient(int timeoutSeconds) {
        try {
            TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }

                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {
                            // 不校验
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                            // 不校验
                        }
                    }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAll, new SecureRandom());
            SSLParameters params = new SSLParameters();
            params.setEndpointIdentificationAlgorithm(null); // 关闭主机名校验

            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .sslContext(sslContext)
                    .sslParameters(params)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("初始化 skip-tls-verify 客户端失败", e);
        }
    }

    /**
     * 异步推送数据。
     *
     * @param data 已组装好的根对象（含 players 与 head）
     * @return 推送结果的 CompletableFuture
     */
    public CompletableFuture<PushResult> pushAsync(Map<String, Object> data) {
        if (!config.isEndpointConfigured()) {
            PushResult result = PushResult.failed("endpoint 或 token 未配置");
            lastResult = result;
            if (config.isDebug()) {
                plugin.getLogger().warning("推送跳过：" + result.message());
            }
            return CompletableFuture.completedFuture(result);
        }

        String json = toJson(data);
        String url = config.getEndpoint()
                + "?token=" + URLEncoder.encode(config.getToken(), StandardCharsets.UTF_8);

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(config.getPushTimeoutSeconds()))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("User-Agent", "MineStatus/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            PushResult result = PushResult.failed("endpoint 地址非法: " + e.getMessage());
            lastResult = result;
            plugin.getLogger().warning(result.message());
            return CompletableFuture.completedFuture(result);
        }

        return http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenApply(response -> {
                    int code = response.statusCode();
                    PushResult result;
                    if (code >= 200 && code < 300) {
                        int playerCount = 0;
                        Object players = data.get("players");
                        if (players instanceof List<?> list) {
                            playerCount = list.size();
                        }
                        result = PushResult.success(code, playerCount, json.length());
                    } else {
                        result = PushResult.failed("HTTP " + code);
                    }
                    lastResult = result;
                    if (config.isDebug()) {
                        plugin.getLogger().info("推送结果: " + result.message());
                    }
                    return result;
                })
                .exceptionally(throwable -> {
                    Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                    PushResult result = PushResult.failed(cause.getClass().getSimpleName() + ": " + cause.getMessage());
                    lastResult = result;
                    if (config.isDebug()) {
                        plugin.getLogger().warning("推送失败: " + result.message());
                    }
                    return result;
                });
    }

    /**
     * 手写 JSON 序列化。
     * <p>
     * 结构固定为：
     * <pre>
     * {
     *   "online": bool,
     *   "time": long,
     *   "max": int,
     *   "version": string,
     *   "players": [ { "name": string, "uuid": string, "head": string } ]
     * }
     * </pre>
     */
    @SuppressWarnings("unchecked")
    private String toJson(Map<String, Object> data) {
        StringBuilder sb = new StringBuilder(512);
        sb.append('{');

        sb.append("\"online\":").append(Boolean.TRUE.equals(data.get("online")));
        sb.append(",\"time\":").append(asLong(data.get("time")));
        sb.append(",\"max\":").append(asLong(data.get("max")));
        sb.append(",\"version\":\"").append(escape(asString(data.get("version")))).append('"');

        sb.append(",\"players\":[");
        Object playersObj = data.get("players");
        if (playersObj instanceof List<?> players) {
            boolean first = true;
            for (Object item : players) {
                if (!(item instanceof Map<?, ?> playerMap)) {
                    continue;
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                Map<String, Object> p = (Map<String, Object>) playerMap;
                sb.append('{');
                sb.append("\"name\":\"").append(escape(asString(p.get("name")))).append('"');
                sb.append(",\"uuid\":\"").append(escape(asString(p.get("uuid")))).append('"');
                sb.append(",\"head\":\"").append(escape(asString(p.get("head")))).append('"');
                sb.append('}');
            }
        }
        sb.append(']');

        sb.append('}');
        return sb.toString();
    }

    private static long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return 0L;
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * JSON 字符串转义。
     * <p>
     * base64 内容只含 A-Za-z0-9+/=，不会被转义，体积不受影响。
     */
    private static String escape(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    public PushResult getLastResult() {
        return lastResult;
    }

    /**
     * 推送结果。
     */
    public record PushResult(boolean success, int statusCode, String message, long timestamp) {

        public static PushResult never() {
            return new PushResult(false, 0, "尚未推送", 0L);
        }

        public static PushResult success(int statusCode, int playerCount, int jsonLength) {
            return new PushResult(true, statusCode,
                    "推送成功 (HTTP " + statusCode + ", " + playerCount + " 名玩家, " + jsonLength + " 字节)",
                    System.currentTimeMillis());
        }

        public static PushResult failed(String message) {
            return new PushResult(false, 0, "推送失败: " + message, System.currentTimeMillis());
        }
    }
}
