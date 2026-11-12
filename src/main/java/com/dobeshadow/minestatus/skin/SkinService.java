package com.dobeshadow.minestatus.skin;

import com.dobeshadow.minestatus.MineStatusPlugin;
import com.dobeshadow.minestatus.config.ConfigManager;
import org.bukkit.entity.Player;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 皮肤头像服务。
 * <p>
 * 负责：取玩家皮肤纹理 → 下载 PNG → 裁出 8x8 面部 → 放大到 64x64 → 编码为 base64 data URI。
 * 结果按「uuid + 纹理标识」缓存，避免每次推送都重复下载与编码。
 * <p>
 * 注意：本类所有方法都应在异步线程调用，内部不触碰 Bukkit 主线程 API
 * （仅读取 Player 的 profile 数据，这在 Paper 下是线程安全的只读操作）。
 */
public class SkinService {

    /** 面部在 64x64 皮肤图中的起始坐标与尺寸 */
    private static final int FACE_X = 8;
    private static final int FACE_Y = 8;
    private static final int FACE_SIZE = 8;
    /** 输出头像尺寸 */
    private static final int OUTPUT_SIZE = 64;

    private final MineStatusPlugin plugin;
    private final ConfigManager config;
    private final HttpClient http;

    /** 缓存：key = uuid + ":" + 纹理标识，value = 缓存条目 */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /** 内置默认头像（懒加载，只生成一次） */
    private volatile String defaultHeadDataUri;

    public SkinService(MineStatusPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(config.getSkinDownloadTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 采集玩家的「真实」头像 data URI。
     * <p>
     * 与 {@link #getHeadDataUri(Player)} 的区别：本方法在任何一步都失败时
     * 返回 null，<b>不会</b>返回内置默认头。调用方据此判断能否写进历史缓存，
     * 避免把兜底 Steve 头当成玩家真实皮肤固化下来。
     * <p>
     * 调用链：玩家 profile 皮肤纹理 → mc-heads.net 头像。
     *
     * @param player 玩家（异步线程读取其 profile）
     * @return 真实头像 data URI，采集失败返回 null
     */
    public String getRealHeadDataUri(Player player) {
        String uuid = player.getUniqueId().toString();
        String name = player.getName();

        // 1. 尝试从玩家 profile 取皮肤纹理 URL（离线服 + SkinsRestorer 时可用）
        URL skinUrl = resolveSkinUrl(player);
        String cacheKey = uuid + ":" + (skinUrl != null ? skinUrl.toString().hashCode() : "fallback");

        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired(config.getSkinCacheMinutes())) {
            return cached.real ? cached.dataUri : null;
        }

        // 2. 下载并裁面部（皮肤纹理图，需要裁 (8,8,8,8)）
        String result = null;
        if (skinUrl != null) {
            result = downloadAndCrop(skinUrl.toString(), true);
        }

        // 3. 兜底：mc-heads.net 按名字取 64x64 头像（已经是头像，直接缩放）
        if (result == null) {
            result = downloadAndCrop(buildMcHeadsUrl(name), false);
        }

        boolean real = result != null;
        // 失败也缓存一小段时间，避免每轮推送都重复外连超时
        cache.put(cacheKey, new CacheEntry(real ? result : "", System.currentTimeMillis(), real));
        return real ? result : null;
    }

    /**
     * 为玩家构建头像 data URI（含内置默认兜底），永不返回 null。
     * 保留给其他可能调用的地方；推送链路改用 {@link #getRealHeadDataUri(Player)}。
     */
    public String getHeadDataUri(Player player) {
        String real = getRealHeadDataUri(player);
        return real != null ? real : getDefaultHeadDataUri();
    }

    /**
     * 从玩家 profile 解析皮肤纹理 URL。
     * 离线服 + SkinsRestorer 场景下通常能拿到；拿不到返回 null。
     */
    private URL resolveSkinUrl(Player player) {
        try {
            org.bukkit.profile.PlayerProfile profile = player.getPlayerProfile();
            if (profile == null) {
                return null;
            }
            org.bukkit.profile.PlayerTextures textures = profile.getTextures();
            if (textures == null) {
                return null;
            }
            return textures.getSkin();
        } catch (Throwable t) {
            // 某些服务端/版本可能不支持该 API，静默降级
            if (config.isDebug()) {
                plugin.getLogger().warning("读取玩家皮肤纹理失败: " + player.getName() + " - " + t.getMessage());
            }
            return null;
        }
    }

    private String buildMcHeadsUrl(String name) {
        return "https://mc-heads.net/avatar/" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "/" + OUTPUT_SIZE;
    }

    /**
     * 下载图片并裁出面部，返回 data URI；失败返回 null。
     *
     * @param url            图片地址
     * @param isSkinTexture  true 表示这是完整皮肤图（需裁 8x8 面部）；
     *                       false 表示这已经是头像图（直接缩放）
     */
    private String downloadAndCrop(String url, boolean isSkinTexture) {
        try {
            byte[] bytes = download(url);
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                return null;
            }
            // 标准皮肤是 64x64（新版）或 64x32（老版）。若拿到尺寸更小的图，
            // 多半是「已渲染的人物立绘」而非皮肤贴图，绝不能整图缩放当头像
            // （这正是之前 SiriusBlack 显示成一整个人的原因）。
            if (isSkinTexture && (image.getWidth() < 64 || image.getHeight() < 32)) {
                if (config.isDebug()) {
                    plugin.getLogger().warning("皮肤图尺寸异常，跳过: " + image.getWidth()
                            + "x" + image.getHeight() + " @ " + url);
                }
                return null;
            }
            BufferedImage head = cropFace(image, isSkinTexture);
            String uri = toDataUri(head);
            // 8x8 像素脸放大后 PNG 通常 < 1KB；体积过大说明带抗锯齿/渐变，不是像素脸
            if (uri.length() > HeadCache.MAX_HEAD_URI_LENGTH) {
                if (config.isDebug()) {
                    plugin.getLogger().warning("头像体积异常，跳过: " + uri.length() + " 字符 @ " + url);
                }
                return null;
            }
            return uri;
        } catch (Exception e) {
            if (config.isDebug()) {
                plugin.getLogger().warning("头像处理失败: " + url + " - " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * 下载原始字节，带 UA 与超时。
     */
    private byte[] download(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(config.getSkinDownloadTimeoutSeconds()))
                    .header("User-Agent", "MineStatus/1.0 (Bukkit Plugin)")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body();
            }
            if (config.isDebug()) {
                plugin.getLogger().warning("下载头像返回非 2xx: " + response.statusCode() + " - " + url);
            }
            return null;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (config.isDebug()) {
                plugin.getLogger().warning("下载头像异常: " + url + " - " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * 裁出面部并放大到 64x64。
     * <p>
     * isSkinTexture = true：标准皮肤图，取 (8,8,8,8) 面部区域；
     * isSkinTexture = false：已经是头像图（如 mc-heads 返回），直接缩放。
     */
    private BufferedImage cropFace(BufferedImage source, boolean isSkinTexture) {
        BufferedImage face;
        int w = source.getWidth();
        int h = source.getHeight();

        if (isSkinTexture && w >= 64 && h >= 32) {
            // 标准皮肤图（64x64 新版 / 64x32 老版）：头部区域都在 (8,8,8,8)
            face = source.getSubimage(FACE_X, FACE_Y, FACE_SIZE, FACE_SIZE);
        } else {
            // 已经是头像图或尺寸不明：整图缩放
            face = source;
        }

        BufferedImage out = new BufferedImage(OUTPUT_SIZE, OUTPUT_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.drawImage(face, 0, 0, OUTPUT_SIZE, OUTPUT_SIZE, null);
        g.dispose();
        return out;
    }

    /**
     * 将图片编码为 base64 data URI。
     */
    private String toDataUri(BufferedImage image) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bos);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    /**
     * 生成内置默认头像（不发起任何外部请求）。
     * 画一个简单的 Steve 风格色块头像。
     */
    private String getDefaultHeadDataUri() {
        String cached = defaultHeadDataUri;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (defaultHeadDataUri != null) {
                return defaultHeadDataUri;
            }
            try {
                BufferedImage img = new BufferedImage(OUTPUT_SIZE, OUTPUT_SIZE, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = img.createGraphics();
                // 背景：深灰
                g.setColor(new Color(0x3A3A3A));
                g.fillRect(0, 0, OUTPUT_SIZE, OUTPUT_SIZE);
                // 头发：深棕
                g.setColor(new Color(0x4A2F1B));
                g.fillRect(0, 0, OUTPUT_SIZE, 16);
                // 皮肤：浅棕
                g.setColor(new Color(0xC08A5A));
                g.fillRect(8, 16, 48, 40);
                // 眼睛
                g.setColor(Color.WHITE);
                g.fillRect(18, 28, 10, 8);
                g.fillRect(36, 28, 10, 8);
                g.setColor(new Color(0x2E4A8B));
                g.fillRect(22, 30, 4, 4);
                g.fillRect(40, 30, 4, 4);
                // 嘴
                g.setColor(new Color(0x7A4B2A));
                g.fillRect(24, 46, 16, 4);
                g.dispose();

                String uri = toDataUri(img);
                defaultHeadDataUri = uri;
                return uri;
            } catch (IOException e) {
                // 理论上不会发生（内存图片），兜底返回空字符串
                return "";
            }
        }
    }

    /**
     * 清空缓存（reload 时调用）。
     */
    public void clearCache() {
        cache.clear();
    }

    /**
     * 缓存条目。
     */
    private static final class CacheEntry {
        final String dataUri;
        final long createdAt;
        /** false 表示这是「采集失败」的负缓存，不能写进历史头像库 */
        final boolean real;

        CacheEntry(String dataUri, long createdAt, boolean real) {
            this.dataUri = dataUri;
            this.createdAt = createdAt;
            this.real = real;
        }

        boolean isExpired(int cacheMinutes) {
            if (cacheMinutes <= 0) {
                return true;
            }
            long ttl = cacheMinutes * 60_000L;
            return System.currentTimeMillis() - createdAt > ttl;
        }
    }
}
