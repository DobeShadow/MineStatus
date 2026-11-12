# MineStatus

采集服务器在线玩家（名字 + 头像），定时推送到 WIKI / NamelessMC 状态页展示。

适用于 **离线模式 + SkinsRestorer** 的服务器：即使服务端隐藏了 ping 玩家名单，也能在网页上显示真实在线玩家头像。

---

## 功能

- 定时（默认 5 秒）采集在线玩家名字与 UUID
- 异步下载皮肤并裁出 64×64 面部头像，编码为 base64 data URI
- 通过 HTTP POST 推送到 PHP 接口，落盘为 `status-cache.json`
- WIKI 与 NamelessMC 门户共用同一份缓存文件
- 头像三级兜底：玩家皮肤纹理 → `mc-heads.net` → 内置默认头像
- 头像缓存（默认 60 分钟），避免重复下载
- **玩家头像历史库（1.1.0 新增）**：把在线时采集到的真实头像按名字跨重启持久化，
  让离线玩家在排行榜上也能显示真实皮肤，而不是清一色默认 Steve（详见下方专节）

---

## 环境要求

| 项 | 要求 |
| --- | --- |
| 服务端 | Purpur / Paper 1.21.x（已在 Purpur 1.21.11 编译验证） |
| Java | 21+（已在 Java 25 编译验证，服务端需 Java 21 及以上） |
| 依赖插件 | 无（SkinsRestorer 可选，装了效果更好） |

---

## 安装

1. 编译或获取 `MineStatus-1.1.0.jar`，放入服务端 `plugins/` 目录
2. 启动服务器一次，生成 `plugins/MineStatus/config.yml`
3. 编辑配置：

```yaml
endpoint: "https://www.minemc.asia/status-ingest.php"
token: "你的随机长串"
```

4. 重启服务器（或 `/minestatus reload`）

---

## 配置说明

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `config-version` | `1` | 配置结构版本，请勿手动修改 |
| `enabled` | `true` | 是否启用自动推送 |
| `interval-seconds` | `5` | 自动推送间隔（秒），最小 1 |
| `endpoint` | — | PHP 接口地址（不含 `?token=`） |
| `token` | — | 与 PHP 中 `TOKEN` 常量一致 |
| `max-players-in-payload` | `100` | 单次推送最多携带玩家数 |
| `head-cache-max-players` | `500` | 历史头像库最多保留玩家数，超出淘汰最久未更新的 |
| `skin-cache-minutes` | `60` | 头像缓存时长（分钟），0 为不缓存 |
| `skin-download-timeout-seconds` | `5` | 皮肤下载超时（秒） |
| `push-timeout-seconds` | `5` | 推送请求超时（秒） |
| `skip-tls-verify` | `false` | 目标站点证书异常时临时跳过 TLS 校验 |
| `debug` | `false` | 是否输出调试日志 |

> **升级说明**：`saveDefaultConfig()` 不会覆盖已有的 `config.yml`。1.1.0 新增的
> `head-cache-max-players` 在旧配置里缺失时会使用默认值 500，功能照常工作，
> 无需删配置；`config-version` 本次未变更，OP 不会收到升级提示。

> **配置版本变更提示**：当插件更新导致 `config-version` 变化时，OP 进服会收到提示，请备份后删除旧 `config.yml` 重新生成。

---

## 常见问题

### 推送报 `SSLHandshakeException: certificate_expired` / 证书校验失败

说明目标站点证书异常（过期、或证书域名不匹配），Java 的 TLS 校验会正确拒绝。两种处理：

1. **临时绕过**（证书暂时修不了时）：`config.yml` 里把 `skip-tls-verify` 设为 `true`
   ```yaml
   skip-tls-verify: true
   ```
   这会让 HTTPS 跳过证书校验。**存在中间人攻击的理论风险**，证书修好后请改回 `false`。
2. **改走 HTTP**：把 `endpoint` 换成 `http://...`（需确保服务器出方向 80 端口可用；部分云主机会拦 80）。

> 根本解决：在主机面板给域名申请/上传有效证书（如 Let's Encrypt），然后保持 `https://` + `skip-tls-verify: false`。

### 推送报 `HttpTimeoutException: request timed out`

通常是服务器出方向到目标端口的网络被拦（例如 80 端口不通），或目标站点响应过慢。优先确认 443 可达；必要时调大 `push-timeout-seconds`。

---

## 命令

| 命令 | 说明 | 权限 |
| --- | --- | --- |
| `/minestatus push` | 立即推送一次 | `minestatus.admin` |
| `/minestatus reload` | 重载配置并重置定时器 | `minestatus.admin` |
| `/minestatus status` | 查看当前状态与上次推送结果 | `minestatus.admin` |
| `/minestatus heads` | 查看玩家头像历史缓存数量与待推送状态 | `minestatus.admin` |

命令支持 Tab 补全。

---

## 服务端部署（PHP）

1. 将 `web/status-ingest.php` 与 `web/default-head.png` 上传到虚拟主机根目录
2. 修改文件内 `TOKEN` 常量，与插件 `config.yml` 的 `token` 保持一致
3. 确认目录可写（会生成 `status-cache.json` 与 `status-heads.json`）
4. 可选：填写 `ALLOWED_IPS` 限制来源 IP，或设置 `MIN_INTERVAL_SECONDS` 做频率限制

验证：

```bash
curl -X POST "https://www.minemc.asia/status-ingest.php?token=你的token" \
  -H "Content-Type: application/json" \
  -d '{"online":true,"time":0,"max":100,"version":"1.20.1","players":[]}'
```

返回 `204` 即成功，随后访问 `https://www.minemc.asia/status-cache.json` 应能看到 JSON。

---

## 前端接入

### VitePress WIKI

参考 `web/ServerStatus.vue.txt`，核心是：

```js
const res = await fetch('/status-cache.json?t=' + Date.now(), { cache: 'no-store' })
```

头像直接使用 `p.head`（base64 data URI），加载失败时替换为站点 favicon。

### NamelessMC 门户

参考 `web/namelessmc-status-snippet.tpl`，插入到 `status.tpl` 的 `{include file='footer.tpl'}` 之前。

> ⚠️ Smarty 模板中内联 CSS/JS 必须用 `{literal}...{/literal}` 包裹，否则整页报错。

---

## 数据格式

```json
{
  "online": true,
  "time": 1762830000,
  "max": 100,
  "version": "1.20.1",
  "players": [
    {
      "name": "Steve",
      "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
      "head": "data:image/png;base64,iVBORw0KGgo..."
    }
  ],
  "heads": {
    "OldPlayer": "data:image/png;base64,iVBORw0KGgo..."
  }
}
```

`heads` 是 **1.1.0 新增的可选字段**，仅当历史头像库有新增/变化时才附带；
PHP 端是合并语义，不会因某轮没带这个字段而清空历史库。

---

## 离线服头像：为什么会有很多默认 Steve

MINEMC 这类 **offline / cracked** 服的玩家 UUID 全是 v3（name-based MD5），
Mojang 账号库里没有这些人。而 `mc-heads.net` / `minotar.net` / `crafatar.com` /
Mojang API 全部走「玩家名 → 正版 UUID → 皮肤」链路，查不到时一律回退默认 Steve。
所以**网页侧按名字查皮肤在离线服是死路**，只能靠服务端插件主动采集。

1.1.0 的做法：

```
玩家在线 → PlayerProfile.getTextures() 取皮肤 → 裁 8x8 脸 → data URI
                                    ├─ 写进本轮 players[].head（给状态页）
                                    └─ 落盘 plugins/MineStatus/heads.json（跨重启）
                                             ↓ 仅在新采集/皮肤变化时
                                       POST 附带 heads 字段
                                             ↓
                              status-ingest.php 合并写 status-heads.json
                                             ↓
                    leaderboard-ingest.php / head.php 按名字回填离线玩家
```

- 玩家只要上线过一次，之后即使离线，榜单也会用他的真实皮肤头。
- `status-cache.json` 的 `players` 仍**只包含当前在线玩家**，WIKI 状态页和
  `/status/` 不会把离线玩家误显示成在线。
- 兜底 Steve 头、异常立绘、采集失败一律**不入库**，避免把假头像固化。
- 皮肤图尺寸必须是 64×64 或 64×32，否则判为「已渲染立绘」直接丢弃；
  生成的 data URI 超过 1600 字符同样丢弃（正常 8×8 像素脸约 600–900 字符）。
  这条同时修掉了「某个玩家头像显示成一整个人」的老问题。

用 `/minestatus heads` 查看已缓存玩家数和待推送状态。

> 想**一次性补全全服老玩家**，只能另外接 SkinsRestorer 的 storage API 按名字批量读
> 离线皮肤；历史缓存方案是渐进式的，随玩家陆续上线逐步覆盖。

---

## 注意事项

- **头像体积**：base64 头像每个约 1–3KB，50 人约 50–150KB。若嫌大，可降低推送频率或改用纹理 URL。
- **离线玩家**：没被历史缓存记录过时只能显示默认 Steve，这是离线 UUID 查不到 Mojang 皮肤导致的，不是故障；详见上方专节。
- **线程安全**：采集在主线程，下载与推送在异步线程，不会阻塞服务器。
- **缓存**：前端务必带时间戳或 `no-store`，否则会看到旧数据。
- **不要自动重启服务器**：配置或插件更新后请手动重启。

---

## 项目结构

```
src/main/java/com/dobeshadow/minestatus/
├── MineStatusPlugin.java          主类：生命周期、调度、模块装配
├── config/ConfigManager.java      配置读取与版本校验
├── collect/PlayerSnapshotService.java  主线程玩家快照
├── skin/SkinService.java          皮肤下载、裁面部、缓存
├── skin/HeadCache.java            玩家头像历史库（持久化 + 增量推送）
├── push/StatusPusher.java         异步 HTTP 推送
└── command/MineStatusCommand.java 命令与 Tab 补全

src/main/resources/
├── plugin.yml
└── config.yml

web/
├── status-ingest.php              PHP 接收接口（含历史头像库合并）
├── default-head.png               默认 Steve 头，异常头像的替换图
├── ServerStatus.vue.txt           VitePress 前端改动参考
└── namelessmc-status-snippet.tpl  NamelessMC 模板片段
```

---

## 作者

DobeShadow
