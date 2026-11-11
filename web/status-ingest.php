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
const MAX_BYTES = 262144;   // 请求体上限 256KB
const MAX_PLAYERS = 100;    // 最多保留玩家数

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
    // 头像体积上限（约 8KB base64）
    if (strlen($head) > 8192) {
        $head = '';
    }

    $clean['players'][] = [
        'name' => $name,
        'uuid' => $uuid,
        'head' => $head,
    ];
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
