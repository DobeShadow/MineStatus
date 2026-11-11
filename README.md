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

---

## 环境要求

| 项 | 要求 |
| --- | --- |
| 服务端 | Purpur / Paper / Spigot 1.20.x |
| Java | 17+（推荐 21） |
| 依赖插件 | 无（SkinsRestorer 可选，装了效果更好） |

---

## 安装

1. 编译或获取 `MineStatus-1.0.0.jar`，放入服务端 `plugins/` 目录
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
| `skin-cache-minutes` | `60` | 头像缓存时长（分钟），0 为不缓存 |
| `skin-download-timeout-seconds` | `5` | 皮肤下载超时（秒） |
| `push-timeout-seconds` | `5` | 推送请求超时（秒） |
| `debug` | `false` | 是否输出调试日志 |

> **配置版本变更提示**：当插件更新导致 `config-version` 变化时，OP 进服会收到提示，请备份后删除旧 `config.yml` 重新生成。

---

## 命令

| 命令 | 说明 | 权限 |
| --- | --- | --- |
| `/minestatus push` | 立即推送一次 | `minestatus.admin` |
| `/minestatus reload` | 重载配置并重置定时器 | `minestatus.admin` |
| `/minestatus status` | 查看当前状态与上次推送结果 | `minestatus.admin` |

命令支持 Tab 补全。

---

## 服务端部署（PHP）

1. 将 `web/status-ingest.php` 上传到虚拟主机根目录
2. 修改文件内 `TOKEN` 常量，与插件 `config.yml` 的 `token` 保持一致
3. 确认目录可写（`status-cache.json` 会生成在同目录）
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
  ]
}
```

---

## 注意事项

- **头像体积**：base64 头像每个约 1–3KB，50 人约 50–150KB。若嫌大，可降低推送频率或改用纹理 URL。
- **离线玩家**：SkinsRestorer 未应用皮肤时，会自动走 `mc-heads.net` 兜底。
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
├── push/StatusPusher.java         异步 HTTP 推送
└── command/MineStatusCommand.java 命令与 Tab 补全

src/main/resources/
├── plugin.yml
└── config.yml

web/
├── status-ingest.php              PHP 接收接口
├── ServerStatus.vue.txt           VitePress 前端改动参考
└── namelessmc-status-snippet.tpl  NamelessMC 模板片段
```

---

## 作者

DobeShadow
