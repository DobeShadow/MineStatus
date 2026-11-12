<?php
/**
 * MineStatus 数据接收接口
 * ------------------------------------------------------------
 * 部署位置：虚拟主机根目录（与 status-cache.json 同目录）
 * 访问方式：POST https://www.minemc.asia/status-ingest.php?token=xxx
 * 数据流向：插件 POST JSON → 本脚本校验 → 原子写入 status-cache.json
 * ------------------------------------------------------------
 * 注意：TOKEN 必须与插件 config.yml 中的 token 完全一致
 */

declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

// ============ 配置区 ============
const TOKEN = 'CHANGE_ME_请替换为与插件一致的随机长串';
const CACHE_FILE = __DIR__ . '/status-cache.json';
const MAX_BYTES = 1048576;  // 请求体上限 1MB（首次推送可能携带全量历史头像）
const MAX_PLAYERS = 100;    // 最多保留玩家数
// 玩家头像历史库：插件只在有新采集时附带 heads 字段，这里做合并持久化
const HEADS_FILE = __DIR__ . '/status-heads.json';
const MAX_CACHED_HEADS = 2000;

// 可选：IP 白名单（填你的 MC 服务器出口 IP，留空表示不限制）
const ALLOWED_IPS = [];

// 可选：频率限制（同一 IP 最小间隔秒数，0 表示不限制）
const MIN_INTERVAL_SECONDS = 0;
const RATE_FILE = __DIR__ . '/status-rate.json';
// ================================

// ---- 1. IP 白名单 ----
if (!empty(ALLOWED_IPS)) {
    $remoteIp = $_SERVER['REMOTE_ADDR'] ?? '';
    if (!in_array($remoteIp, ALLOWED_IPS, true)) {
        http_response_code(403);
        echo '{"ok":false,"error":"ip_not_allowed"}';
        exit;
    }
}

// ---- 2. Token 校验 ----
$token = $_GET['token'] ?? '';
if (!hash_equals(TOKEN, (string)$token)) {
    http_response_code(403);
    echo '{"ok":false,"error":"invalid_token"}';
    exit;
}

// ---- 3. 方法校验 ----
if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    http_response_code(405);
    echo '{"ok":false,"error":"method_not_allowed"}';
    exit;
}

// ---- 4. 频率限制（可选） ----
if (MIN_INTERVAL_SECONDS > 0) {
    $remoteIp = $_SERVER['REMOTE_ADDR'] ?? 'unknown';
    $now = time();
    $rates = [];
    if (is_file(RATE_FILE)) {
        $raw = @file_get_contents(RATE_FILE);
        if ($raw !== false) {
            $decoded = json_decode($raw, true);
            if (is_array($decoded)) {
                $rates = $decoded;
            }
        }
    }
    $last = (int)($rates[$remoteIp] ?? 0);
    if ($now - $last < MIN_INTERVAL_SECONDS) {
        http_response_code(429);
        echo '{"ok":false,"error":"too_many_requests"}';
        exit;
    }
    $rates[$remoteIp] = $now;
    @file_put_contents(RATE_FILE, json_encode($rates), LOCK_EX);
}

// ---- 5. 读取请求体 ----
$raw = file_get_contents('php://input');
if ($raw === false || strlen($raw) === 0) {
    http_response_code(400);
    echo '{"ok":false,"error":"empty_body"}';
    exit;
}
if (strlen($raw) > MAX_BYTES) {
    http_response_code(413);
    echo '{"ok":false,"error":"payload_too_large"}';
    exit;
}

// ---- 6. 解析 JSON ----
$data = json_decode($raw, true);
if (!is_array($data) || !isset($data['players']) || !is_array($data['players'])) {
    http_response_code(400);
    echo '{"ok":false,"error":"invalid_json"}';
    exit;
}

// ---- 7. 清洗字段（防注入 / 防超长） ----
// MineStatus 对少数自定义皮肤会把「整张皮肤 / 渲染立绘」当成头像塞进来
// （带抗锯齿、几百种颜色、~2900 字符），正常 8x8 像素脸只有 ~600-900 字符。
// 这类异常头像在网页上会显示成一整个人，统一换成默认 Steve 头。
$defaultHead = '';
$defaultHeadFile = __DIR__ . '/default-head.png';
if (is_file($defaultHeadFile)) {
    $rawHead = @file_get_contents($defaultHeadFile);
    if ($rawHead !== false && substr($rawHead, 0, 8) === "\x89PNG\r\n\x1a\n") {
        $defaultHead = 'data:image/png;base64,' . base64_encode($rawHead);
    }
}

$clean = [
    'online'  => (bool)($data['online'] ?? false),
    'time'    => (int)($data['time'] ?? time()),
    'max'     => (int)($data['max'] ?? 0),
    'version' => substr((string)($data['version'] ?? ''), 0, 32),
    'players' => [],
];

foreach ($data['players'] as $p) {
    if (count($clean['players']) >= MAX_PLAYERS) {
        break;
    }
    if (!is_array($p) || empty($p['name'])) {
        continue;
    }
    $name = substr((string)$p['name'], 0, 32);
    $uuid = substr((string)($p['uuid'] ?? ''), 0, 64);
    $head = (string)($p['head'] ?? '');

    // 只接受 data URI 或 http(s) URL，其他一律丢弃
    if ($head !== '' && !preg_match('#^(data:image/[a-z]+;base64,|https?://)#i', $head)) {
        $head = '';
    }
    // 头像体积上限；超过 1600 字符基本可判定不是 8x8 像素头像
    if (strlen($head) > 1600) {
        $head = '';
    }
    // 没有头像（或被上面拦掉）时用默认 Steve 头，避免前端兜底到 403 的第三方站
    if ($head === '' && $defaultHead !== '') {
        $head = $defaultHead;
    }

    $clean['players'][] = [
        'name' => $name,
        'uuid' => $uuid,
        'head' => $head,
    ];
}

// 在线人数（以实际玩家列表为准，避免上游未提供时为空）
$clean['numplayers'] = count($clean['players']);

// ---- 7.5 合并玩家头像历史库 ----
// 离线服玩家在 Mojang / minotar 查不到皮肤，网页侧只能显示默认 Steve。
// MineStatus 1.1.0 起会把「在线时采集到的真实头像」按名字累积并推送，
// 这里合并进独立文件，供排行榜 / head.php 给离线玩家补真头像。
// 注意：绝不写进 status-cache.json，否则 WIKI 状态页会把离线玩家当成在线。
if (isset($data['heads']) && is_array($data['heads'])) {
    $stored = [];
    if (is_file(HEADS_FILE)) {
        $prev = json_decode((string)@file_get_contents(HEADS_FILE), true);
        if (is_array($prev) && isset($prev['heads']) && is_array($prev['heads'])) {
            $stored = $prev['heads'];
        }
    }
    foreach ($data['heads'] as $hName => $hUri) {
        if (!is_string($hName) || !preg_match('/^[A-Za-z0-9_]{1,32}$/', $hName)) {
            continue;
        }
        if (!is_string($hUri) || strlen($hUri) > 1600
            || !preg_match('#^data:image/png;base64,[A-Za-z0-9+/=]+$#', $hUri)) {
            continue;
        }
        $stored[$hName] = $hUri;   // 同名覆盖：玩家换皮肤后以最新为准
    }
    if (count($stored) > MAX_CACHED_HEADS) {
        $stored = array_slice($stored, -MAX_CACHED_HEADS, null, true);
    }
    $headsJson = json_encode([
        'time'  => time(),
        'count' => count($stored),
        'heads' => $stored,
    ], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    if ($headsJson !== false) {
        $hTmp = HEADS_FILE . '.tmp';
        if (@file_put_contents($hTmp, $headsJson, LOCK_EX) !== false) {
            @chmod($hTmp, 0644);
            if (!@rename($hTmp, HEADS_FILE)) {
                @unlink($hTmp);
            }
        }
    }
}

// ---- 8. 原子写入 ----
$json = json_encode($clean, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
if ($json === false) {
    http_response_code(500);
    echo '{"ok":false,"error":"encode_failed"}';
    exit;
}

$tmp = CACHE_FILE . '.tmp';
if (@file_put_contents($tmp, $json, LOCK_EX) === false) {
    http_response_code(500);
    echo '{"ok":false,"error":"write_failed"}';
    exit;
}
@chmod($tmp, 0644);

if (!@rename($tmp, CACHE_FILE)) {
    @unlink($tmp);
    http_response_code(500);
    echo '{"ok":false,"error":"rename_failed"}';
    exit;
}

// ---- 9. 成功 ----
http_response_code(204);
