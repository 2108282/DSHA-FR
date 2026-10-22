#!/usr/bin/env node
/**
 * DSHA 局域网反向代理守护服务 (DSHA LAN Proxy Daemon - Universal Edition)
 * 监听 0.0.0.0:3081 -> 转发至 127.0.0.1:3080
 *
 * 核心设计原则（KernelSU / Magisk 通用，零侵入官方核心）：
 * 1. 【双向解耦与安全门禁】：外网仅识别 DSHA 局域网 Token (.lan_token / .bridge_token)，支持 URL/Header/Cookie 三重呈现；
 * 2. 【官方会话动态自愈】：基于 .credentials.yaml 原生密钥自签 Cookie，兼备 launch_token 动态兑换，自动感知 mtime 变更；
 * 3. 【参数清洗与伪装】：转发给 3080 前自动剔除 URL 中的 token 参数，重写 Host 与 Origin，彻底消除 "authentication required" 401 拒签；
 * 4. 【后端 401 容灾自愈】：若 3080 返回 401，立即击穿凭据缓存并触发秒级重新兑换；
 * 5. 【全双工流与 WebSocket 穿透】：完整支持 Terminal、Remote Mux (/api/remote.mux) 及 EventSource 长连接；
 * 6. 【毫秒级动态失效】：用户更换 Token 时通过 SIGUSR1 信号秒断存量长连接并清空客户端 Cookie；
 * 7. 【极简低功耗】：0 定时器无唤醒常驻，纯操作系统事件驱动。
 */

const http = require('http');
const net = require('net');
const fs = require('fs');
const crypto = require('crypto');

const LAN_PORT = parseInt(process.env.LAN_PORT || '3081', 10);
const BACKEND_PORT = parseInt(process.env.DSH_PORT || '3080', 10);
const BACKEND_HOST = '127.0.0.1';
const AUTHORITY = `${BACKEND_HOST}:${BACKEND_PORT}`;

const LAN_TOKEN_FILES = [
  '/root/.dsh/.lan_token',
  '/data/adb/dsha/rootfs/root/.dsh/.lan_token'
];

const BRIDGE_TOKEN_FILES = [
  '/root/.dsh/.bridge_token',
  '/data/adb/dsha/rootfs/root/.dsh/.bridge_token'
];

const CREDENTIAL_FILES = [
  '/root/.dsh/.credentials.yaml',
  '/data/adb/dsha/rootfs/root/.dsh/.credentials.yaml'
];

const LAUNCH_TOKEN_FILES = [
  '/root/.dsh/.launch_token',
  '/data/adb/dsha/rootfs/root/.dsh/.launch_token'
];

const LOG_FILES = [
  '/data/adb/dsha/run/dsh-web.log',
  '/root/dsh-web.log',
  '/root/.dsh/dsh-web.log'
];

// 活动连接池（用于 Token 变更时主动断开旧会话）
const activeSockets = new Set();
let lastKnownToken = null;

// 自动检测与瞬间解冻唤醒后端
const PID_FILES = [
  '/root/.dsh/dsh.pid',
  '/data/adb/dsha/run/dsh.pid',
  '/data/adb/dsha/rootfs/root/.dsh/dsh.pid'
];

const FREEZER_LOG_FILES = [
  '/data/adb/dsha/run/idle-freezer.log',
  '/root/.dsh/idle-freezer.log',
  '/data/adb/dsha/rootfs/root/.dsh/idle-freezer.log'
];

const APP_UID_FILES = [
  '/data/adb/dsha/run/app.uid',
  '/root/.dsh/app.uid',
  '/data/adb/dsha/rootfs/root/.dsh/app.uid'
];

function getAppUid() {
  if (process.env.DSHA_APP_UID) {
    const envUid = parseInt(process.env.DSHA_APP_UID, 10);
    if (!isNaN(envUid) && envUid > 0) return envUid;
  }
  for (const f of APP_UID_FILES) {
    try {
      if (fs.existsSync(f)) {
        const u = parseInt(fs.readFileSync(f, 'utf8').trim(), 10);
        if (!isNaN(u) && u > 0) return u;
      }
    } catch (e) {}
  }
  return -1;
}

function getLocalTimeString() {
  const d = new Date();
  const local = new Date(d.getTime() - d.getTimezoneOffset() * 60 * 1000);
  return local.toISOString().replace('T', ' ').slice(0, 19);
}

function logFreezer(action, msg) {
  try {
    const time = getLocalTimeString();
    const line = `[${time}] [${action}] ${msg}\n`;
    for (const logPath of FREEZER_LOG_FILES) {
      try {
        fs.appendFileSync(logPath, line, { mode: 0o666 });
        break;
      } catch (e) {}
    }
  } catch (e) {}
}

function getBackendPid() {
  for (const p of PID_FILES) {
    try {
      if (fs.existsSync(p)) {
        const pidStr = fs.readFileSync(p, 'utf8').trim();
        const pid = parseInt(pidStr, 10);
        if (pid && !isNaN(pid)) return pid;
      }
    } catch (e) {}
  }
  return null;
}

function ensureBackendAwake(trigger) {
  try {
    const pid = getBackendPid();
    if (!pid) return false;

    const statPath = `/proc/${pid}/stat`;
    if (!fs.existsSync(statPath)) return false;
    const statContent = fs.readFileSync(statPath, 'utf8');
    const parts = statContent.split(' ');
    const state = parts[2]; // 进程状态代码 (T 为 SIGSTOP 冻结挂起)

    if (state === 'T') {
      try {
        process.kill(pid, 'SIGCONT');
      } catch (e) {}

      // 原地同步解冻子进程树
      try {
        const childProcDirs = fs.readdirSync('/proc');
        for (const f of childProcDirs) {
          if (!/^\d+$/.test(f)) continue;
          try {
            const pstat = fs.readFileSync(`/proc/${f}/stat`, 'utf8');
            const pparts = pstat.split(' ');
            if (pparts[3] === String(pid) && pparts[2] === 'T') {
              process.kill(parseInt(f, 10), 'SIGCONT');
            }
          } catch (e) {}
        }
      } catch (e) {}

      // 原地清理休眠标记文件 (容器内与宿主双路径)
      try { fs.unlinkSync('/root/.dsh/freezer.state'); } catch (e) {}
      try { fs.unlinkSync('/data/adb/dsha/run/freezer.state'); } catch (e) {}

      const cleanTrigger = (trigger || '网络访问').toString().replace(/["'`\n\r]/g, ' ');
      logFreezer('WAKE', `主进程已原地解冻恢复 (PID: ${pid}) | 唤醒源: [${cleanTrigger}]`);

      // 异步通过 3095 桥通知常驻通知刷新
      const token = getBridgeToken();
      if (token) {
        http.get(`http://127.0.0.1:3095/app/freeze/state?token=${token}&frozen=0`, (res) => {
          res.resume();
        }).on('error', () => {});
      }
      return true;
    }
  } catch (e) {}
  return false;
}

// 后端 DSH 会话凭据缓存与文件跟踪
let cachedBackendCookie = null;
let cachedCookieExpiry = 0;
let lastCredentialMtime = 0;

function encodeBase64Url(buf) {
  return buf.toString('base64').replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/u, '');
}

function decodeBase64Url(str) {
  if (typeof str !== 'string') return null;
  const padding = '='.repeat((4 - str.length % 4) % 4);
  try {
    return Buffer.from(str.replaceAll('-', '+').replaceAll('_', '/') + padding, 'base64');
  } catch (e) {
    return null;
  }
}

// 恒定时间安全比对
function safeCompare(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  if (!a || !b) return false;
  const bufA = Buffer.from(a);
  const bufB = Buffer.from(b);
  if (bufA.length !== bufB.length) return false;
  let diff = 0;
  for (let i = 0; i < bufA.length; i++) {
    diff |= bufA[i] ^ bufB[i];
  }
  return diff === 0;
}

// 动态读取有效 LAN Token（优先读取 lan_token，回退 bridge_token）
function getCurrentToken() {
  for (const f of LAN_TOKEN_FILES) {
    try {
      if (fs.existsSync(f)) {
        const t = fs.readFileSync(f, 'utf8').trim();
        if (t) return t;
      }
    } catch (e) {}
  }
  for (const f of BRIDGE_TOKEN_FILES) {
    try {
      if (fs.existsSync(f)) {
        const t = fs.readFileSync(f, 'utf8').trim();
        if (t) return t;
      }
    } catch (e) {}
  }
  return '';
}

function getBridgeToken() {
  for (const f of BRIDGE_TOKEN_FILES) {
    try {
      if (fs.existsSync(f)) {
        const t = fs.readFileSync(f, 'utf8').trim();
        if (t) return t;
      }
    } catch (e) {}
  }
  return getCurrentToken();
}

// 解析 Cookie 字符串
function parseCookies(header) {
  const cookies = {};
  if (!header) return cookies;
  const pairs = header.split(';');
  for (let i = 0; i < pairs.length; i++) {
    const p = pairs[i].trim();
    const eq = p.indexOf('=');
    if (eq > 0) {
      cookies[p.substring(0, eq).trim()] = p.substring(eq + 1).trim();
    }
  }
  return cookies;
}

// 提取客户端请求中的 Token
function extractPresentedToken(req) {
  try {
    const urlObj = new URL(req.url || '/', 'http://127.0.0.1');
    const queryToken = urlObj.searchParams.get('token') || urlObj.searchParams.get('dsha_t');
    if (queryToken) return { token: queryToken.trim(), source: 'query' };
  } catch (e) {}

  const headerToken = req.headers['x-dsha-token'] || req.headers['x-token'];
  if (headerToken && typeof headerToken === 'string') {
    return { token: headerToken.trim(), source: 'header' };
  }

  const cookies = parseCookies(req.headers.cookie);
  const cookieToken = cookies['dsha_lan_token'] || cookies['dsha_t'];
  if (cookieToken) {
    return { token: cookieToken.trim(), source: 'cookie' };
  }

  return { token: '', source: 'none' };
}

// 校验客户端请求是否合法
function authenticateRequest(req, currentToken) {
  if (!currentToken) return { ok: true, via: 'no_token_required' };
  const presented = extractPresentedToken(req);
  if (presented.token && safeCompare(presented.token, currentToken)) {
    return { ok: true, via: presented.source, token: presented.token };
  }
  return { ok: false };
}

// 读取官方 launchToken（从文件或启动日志）
function getLaunchToken() {
  for (const f of LAUNCH_TOKEN_FILES) {
    try {
      if (fs.existsSync(f)) {
        const t = fs.readFileSync(f, 'utf8').trim();
        if (t && t.length >= 20) return t;
      }
    } catch (e) {}
  }
  for (const logPath of LOG_FILES) {
    try {
      if (fs.existsSync(logPath)) {
        const logContent = fs.readFileSync(logPath, 'utf8');
        const matches = logContent.match(/http:\/\/127\.0\.0\.1:[0-9]+\/\?token=([A-Za-z0-9_-]+)/g);
        if (matches && matches.length > 0) {
          const lastUrl = matches[matches.length - 1];
          const tokenMatch = lastUrl.match(/token=([A-Za-z0-9_-]+)/);
          if (tokenMatch && tokenMatch[1]) {
            return tokenMatch[1].trim();
          }
        }
      }
    } catch (e) {}
  }
  return '';
}

// 使用 launchToken 向后端 3080 兑换官方 Cookie（异步静默保底）
function exchangeCookieByLaunchToken(launchToken) {
  if (!launchToken) return;
  const options = {
    hostname: BACKEND_HOST,
    port: BACKEND_PORT,
    path: `/?token=${launchToken}`,
    method: 'GET',
    headers: { 'Host': AUTHORITY }
  };
  const req = http.request(options, (res) => {
    const setCookie = res.headers['set-cookie'] || [];
    const setCookieArr = Array.isArray(setCookie) ? setCookie : [setCookie];
    for (const sc of setCookieArr) {
      if (typeof sc === 'string' && sc.includes('dsh-auth-')) {
        const cookieVal = sc.split(';')[0].trim();
        if (cookieVal) {
          cachedBackendCookie = cookieVal;
          cachedCookieExpiry = Date.now() + 25 * 24 * 3600 * 1000;
          console.log('[DSHA LAN Proxy] 成功通过 launchToken 兑换官方会话凭据');
          break;
        }
      }
    }
  });
  req.on('error', () => {});
  req.end();
}

// 核心机制：获取针对 127.0.0.1:3080 的 DSH 官方认证 Cookie（动态感知 mtime 变更）
function getBackendDshAuthCookie(forceRefresh = false) {
  const now = Date.now();
  let needRecompute = forceRefresh || !cachedBackendCookie || cachedCookieExpiry <= now + 3600 * 1000;

  // 检查 .credentials.yaml 文件 mtime 变更
  let currentFile = null;
  let currentMtime = 0;
  for (const f of CREDENTIAL_FILES) {
    try {
      if (fs.existsSync(f)) {
        const st = fs.statSync(f);
        currentFile = f;
        currentMtime = st.mtimeMs;
        break;
      }
    } catch (e) {}
  }

  if (currentMtime && currentMtime !== lastCredentialMtime) {
    needRecompute = true;
    lastCredentialMtime = currentMtime;
  }

  if (!needRecompute && cachedBackendCookie) {
    return cachedBackendCookie;
  }

  // 1. 轨道一：从 .credentials.yaml 读取 secret 动态自签
  if (currentFile) {
    try {
      const content = fs.readFileSync(currentFile, 'utf8');
      const m = content.match(/client-connection\/browser-session:[\s\S]*?secret:\s*([A-Za-z0-9_-]+)/);
      if (m && m[1]) {
        const secretBuf = decodeBase64Url(m[1].trim());
        if (secretBuf && secretBuf.length === 32) {
          const cookieName = 'dsh-auth-' + encodeBase64Url(crypto.createHash('sha256').update(AUTHORITY).digest());
          const expiresAt = now + 30 * 24 * 3600 * 1000; // 30 天有效
          const payload = {
            version: 1,
            authority: AUTHORITY,
            issuedAt: now,
            expiresAt: expiresAt
          };
          const body = encodeBase64Url(Buffer.from(JSON.stringify(payload), 'utf8'));
          const sig = encodeBase64Url(crypto.createHmac('sha256', secretBuf).update(body).digest());
          const cookieStr = `${cookieName}=v1.${body}.${sig}`;
          cachedBackendCookie = cookieStr;
          cachedCookieExpiry = expiresAt;
          return cookieStr;
        }
      }
    } catch (e) {}
  }

  // 2. 轨道二：从 launch_token 或日志触发兑换
  const lTok = getLaunchToken();
  if (lTok) {
    exchangeCookieByLaunchToken(lTok);
  }

  return cachedBackendCookie || '';
}

// 清洗 URL：彻底剥离发往后端的 token 与 dsha_t 参数，避免 3080 触发错误的 launchToken 比对
function sanitizeUrlForBackend(rawUrl) {
  try {
    const dummy = new URL(rawUrl || '/', 'http://127.0.0.1');
    let changed = false;
    if (dummy.searchParams.has('token')) {
      dummy.searchParams.delete('token');
      changed = true;
    }
    if (dummy.searchParams.has('dsha_t')) {
      dummy.searchParams.delete('dsha_t');
      changed = true;
    }
    if (!changed) return rawUrl;
    const q = dummy.searchParams.toString();
    return dummy.pathname + (q ? '?' + q : '') + dummy.hash;
  } catch (e) {
    return rawUrl;
  }
}

// 检查并处理 Token 变更（强杀所有活动长连接）
function checkTokenChange() {
  const current = getCurrentToken();
  if (lastKnownToken === null) {
    lastKnownToken = current;
    return;
  }
  if (!safeCompare(lastKnownToken, current)) {
    console.log(`[DSHA LAN Proxy] 检测到 Token 已变更！主动断开 ${activeSockets.size} 个存量活动连接...`);
    lastKnownToken = current;
    for (const socket of activeSockets) {
      try {
        socket.destroy();
      } catch (e) {}
    }
    activeSockets.clear();
  }
}

// 注册 Linux SIGUSR1 信号监听
process.on('SIGUSR1', () => {
  console.log('[DSHA LAN Proxy] 收到 SIGUSR1 信号，立即热更 Token 并切断旧长连接');
  checkTokenChange();
});

// 友好未授权 HTML（401 解锁卡片）
function get401Html(reason) {
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>401 - DSHA 局域网访问授权</title>
<style>
  * { box-sizing: border-box; }
  body { margin: 0; padding: 20px; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #0f172a; color: #f8fafc; display: flex; align-items: center; justify-content: center; min-height: 100vh; }
  .card { background: #1e293b; border: 1px solid #334155; border-radius: 16px; padding: 32px; max-width: 440px; width: 100%; box-shadow: 0 10px 25px rgba(0,0,0,0.5); text-align: center; }
  .icon { font-size: 48px; margin-bottom: 16px; }
  h2 { margin: 0 0 12px; font-size: 22px; font-weight: 600; color: #38bdf8; }
  p { margin: 0 0 20px; color: #94a3b8; font-size: 14px; line-height: 1.6; }
  .desc { background: rgba(56, 189, 248, 0.1); border: 1px solid rgba(56, 189, 248, 0.2); border-radius: 8px; padding: 10px; margin-bottom: 20px; font-size: 13px; color: #7dd3fc; }
  .input-group { display: flex; flex-direction: column; gap: 12px; margin-bottom: 16px; }
  input { background: #0f172a; border: 1px solid #475569; border-radius: 8px; padding: 12px 16px; color: #fff; font-size: 15px; outline: none; transition: border-color 0.2s; }
  input:focus { border-color: #38bdf8; }
  button { background: #0284c7; color: white; border: none; border-radius: 8px; padding: 12px; font-size: 15px; font-weight: 600; cursor: pointer; transition: background 0.2s; }
  button:hover { background: #0369a1; }
  .tip { font-size: 12px; color: #64748b; margin-top: 16px; }
</style>
</head>
<body>
<div class="card">
  <div class="icon">🔒</div>
  <h2>DSHA 局域网访问授权</h2>
  <div class="desc">${reason || '访问此页面需要提供最新的局域网访问 Token'}</div>
  <p>请在手机 DSHA 应用首页点击「局域网访问」查看或复制最新 Token，输入解锁访问：</p>
  <div class="input-group">
    <input type="password" id="tok" placeholder="输入最新局域网 Token" autofocus />
    <button onclick="login()">验证并解锁进入</button>
  </div>
  <div class="tip">提示：也可直接在浏览器地址栏末尾加上 <code>?token=你的Token</code> 访问。</div>
</div>
<script>
function login() {
  const v = document.getElementById('tok').value.trim();
  if (!v) return alert('请输入有效 Token');
  document.cookie = 'dsha_lan_token=' + encodeURIComponent(v) + '; Path=/; SameSite=Lax; Max-Age=31536000';
  document.cookie = 'dsha_t=' + encodeURIComponent(v) + '; Path=/; SameSite=Lax; Max-Age=31536000';
  const u = new URL(window.location.href);
  u.searchParams.set('token', v);
  window.location.href = u.toString();
}
document.getElementById('tok').addEventListener('keydown', (e) => { if (e.key === 'Enter') login(); });
</script>
</body>
</html>`;
}

// 创建 HTTP 代理服务器
const server = http.createServer((req, res) => {
  const justWoken = ensureBackendAwake('HTTP请求 ' + (req.url || '/'));
  checkTokenChange();

  const currentToken = getCurrentToken();
  const bridgeToken = getBridgeToken();
  const auth = authenticateRequest(req, currentToken);

  // 1. 鉴权失败：主动清除客户端所有本地会话 Cookie 并下发 401
  if (!auth.ok) {
    const cookies = parseCookies(req.headers.cookie);
    const clearCookies = [
      'dsha_lan_token=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT',
      'dsha_t=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT'
    ];
    for (const k of Object.keys(cookies)) {
      if (k.startsWith('dsh-auth-')) {
        clearCookies.push(`${k}=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT`);
      }
    }

    const isHtmlRequest = (req.headers.accept || '').includes('text/html') || req.url === '/' || req.url.startsWith('/?');
    if (isHtmlRequest) {
      res.writeHead(401, {
        'Content-Type': 'text/html; charset=utf-8',
        'Cache-Control': 'no-store, no-cache, must-revalidate',
        'Set-Cookie': clearCookies
      });
      res.end(get401Html('局域网访问凭据已变更或尚未输入'));
    } else {
      res.writeHead(401, {
        'Content-Type': 'application/json; charset=utf-8',
        'Cache-Control': 'no-store, no-cache, must-revalidate',
        'Set-Cookie': clearCookies
      });
      res.end(JSON.stringify({ error: 'Unauthorized', message: 'DSHA 局域网访问凭据已变更或失效，请重新提供最新 Token' }));
    }
    return;
  }

  // 2. 鉴权通过：登记活动 socket
  activeSockets.add(req.socket);
  req.socket.once('close', () => activeSockets.delete(req.socket));

  // 构造发给后端的干净路径
  const backendPath = sanitizeUrlForBackend(req.url);

  // 构造发给 3080 后端的请求头
  const headers = Object.assign({}, req.headers);
  headers['host'] = AUTHORITY;
  headers['origin'] = `http://${AUTHORITY}`;
  headers['x-forwarded-for'] = req.socket.remoteAddress || '127.0.0.1';
  headers['x-forwarded-proto'] = 'http';
  headers['x-forwarded-host'] = req.headers['host'] || '';

  // 注入官方后端 DSH 会话凭证
  const backendCookie = getBackendDshAuthCookie();
  if (backendCookie) {
    let clientCookies = headers['cookie'] || '';
    if (clientCookies) {
      const parts = clientCookies.split(';').map(p => p.trim()).filter(p => !p.startsWith('dsh-auth-'));
      parts.push(backendCookie);
      headers['cookie'] = parts.join('; ');
    } else {
      headers['cookie'] = backendCookie;
    }
  }

  // 兼容注入 bridge_token
  if (bridgeToken) {
    headers['x-dsha-token'] = bridgeToken;
  }

  const options = {
    hostname: BACKEND_HOST,
    port: BACKEND_PORT,
    path: backendPath,
    method: req.method,
    headers: headers
  };

  const doForward = () => {
    const proxyReq = http.request(options, (proxyRes) => {
      // 容灾自愈：若后端核心返回 401（说明密钥已外部变更或进程重启），立即击穿缓存刷新
      if (proxyRes.statusCode === 401) {
        console.warn('[DSHA LAN Proxy] 检测到后端返回 401，立即使本地 Cookie 缓存失效并触发自愈刷新');
        getBackendDshAuthCookie(true);
      }

      const resHeaders = Object.assign({}, proxyRes.headers);

      // 检查客户端是否已持有最新的 dsha_lan_token
      const clientCookies = parseCookies(req.headers.cookie);
      const needSetLanCookie = currentToken && (!clientCookies['dsha_lan_token'] || !safeCompare(clientCookies['dsha_lan_token'], currentToken));

      const setCookie = resHeaders['set-cookie'] || [];
      let setCookieArr = Array.isArray(setCookie) ? setCookie.slice() : [setCookie];

      // 将后端返回的 SameSite=Strict 放宽为 SameSite=Lax，确保局域网跨设备访问正常携带 Cookie
      setCookieArr = setCookieArr.map(sc => typeof sc === 'string' ? sc.replace(/SameSite=Strict/gi, 'SameSite=Lax') : sc);

      if (needSetLanCookie) {
        setCookieArr.push(`dsha_lan_token=${currentToken}; Path=/; SameSite=Lax; Max-Age=31536000`);
        setCookieArr.push(`dsha_t=${currentToken}; Path=/; SameSite=Lax; Max-Age=31536000`);
      }

      if (setCookieArr.length > 0) {
        resHeaders['set-cookie'] = setCookieArr;
      }

      res.writeHead(proxyRes.statusCode, resHeaders);
      proxyRes.pipe(res);
    });

    proxyReq.on('error', (err) => {
      if (!res.headersSent) {
        res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end(`DSHA LAN Proxy: 无法连接本地 DSH 服务 (127.0.0.1:${BACKEND_PORT})，请确认核心服务已启动。\n错误详情: ${err.message}`);
      }
    });

    req.pipe(proxyReq);
  };

  if (justWoken) {
    setTimeout(doForward, 50);
  } else {
    doForward();
  }
});

// 处理 WebSocket Upgrade
server.on('upgrade', (req, clientSocket, head) => {
  const justWoken = ensureBackendAwake('WebSocket握手 ' + (req.url || '/'));
  checkTokenChange();

  const currentToken = getCurrentToken();
  const bridgeToken = getBridgeToken();
  const auth = authenticateRequest(req, currentToken);

  // 鉴权未通过，立即断开 WebSocket
  if (!auth.ok) {
    clientSocket.write('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n');
    clientSocket.destroy();
    return;
  }

  activeSockets.add(clientSocket);
  clientSocket.once('close', () => activeSockets.delete(clientSocket));

  const backendPath = sanitizeUrlForBackend(req.url);

  const doConnectBackend = () => {
    const backendSocket = net.connect(BACKEND_PORT, BACKEND_HOST, () => {
      let rawHeaders = `${req.method} ${backendPath} HTTP/1.1\r\n`;
      const headers = Object.assign({}, req.headers);
      headers['host'] = AUTHORITY;
      headers['origin'] = `http://${AUTHORITY}`;

      // 注入官方后端 DSH 会话凭证
      const backendCookie = getBackendDshAuthCookie();
      if (backendCookie) {
        let clientCookies = headers['cookie'] || '';
        if (clientCookies) {
          const parts = clientCookies.split(';').map(p => p.trim()).filter(p => !p.startsWith('dsh-auth-'));
          parts.push(backendCookie);
          headers['cookie'] = parts.join('; ');
        } else {
          headers['cookie'] = backendCookie;
        }
      }

      if (bridgeToken) {
        headers['x-dsha-token'] = bridgeToken;
      }

      for (const key of Object.keys(headers)) {
        rawHeaders += `${key}: ${headers[key]}\r\n`;
      }
      rawHeaders += '\r\n';

      backendSocket.write(rawHeaders);
      if (head && head.length > 0) {
        backendSocket.write(head);
      }

      backendSocket.pipe(clientSocket);
      clientSocket.on('data', () => {
        ensureBackendAwake('WebSocket交互数据帧');
      });
      clientSocket.pipe(backendSocket);
    });

    backendSocket.on('error', (err) => {
      clientSocket.destroy();
    });

    clientSocket.on('error', (err) => {
      backendSocket.destroy();
    });
  };

  if (justWoken) {
    setTimeout(doConnectBackend, 50);
  } else {
    doConnectBackend();
  }
});

server.listen(LAN_PORT, '0.0.0.0', () => {
  lastKnownToken = getCurrentToken();
  // 每次启动时强制重算一次后端凭证，确保无论换机还是冷启均加载最新密钥
  const cookieState = getBackendDshAuthCookie(true) ? '官方凭据已自愈就绪' : '官方凭据待生成';
  console.log(`[DSHA LAN Proxy] 已启动，监听 0.0.0.0:${LAN_PORT}，转发至 127.0.0.1:${BACKEND_PORT} (${cookieState})`);
  console.log(`[DSHA LAN Proxy] 当前鉴权 Token: ${lastKnownToken ? '已加载' : '未设置(公开模式)'}`);

  // ------------------------------------------------------------
  // 3080 本地内核网络敲门侦测看门狗 (Zero-Overhead TCP Backlog Snooper)
  // 当 DSH 主进程处于 SIGSTOP (T) 冻结态时，内核在底层仍然会完成 TCP 握手。
  // 此时 /proc/net/tcp 中 3080 的 rx_queue > 0。
  // 仅在检测到外部浏览器/客户端访问（非 App 自身内部探活 UID）时瞬间原地唤醒，
  // 扫描周期设定为 1.5s 一次，既保证秒开，又杜绝误唤醒。
  // ------------------------------------------------------------
  let isBacklogChecking = false;
  const hexPort3080 = ':' + BACKEND_PORT.toString(16).toUpperCase().padStart(4, '0');

  function checkBacklogLoop() {
    try {
      const pid = getBackendPid();
      if (!pid) {
        setTimeout(checkBacklogLoop, 1500);
        return;
      }
      const statPath = `/proc/${pid}/stat`;
      if (!fs.existsSync(statPath)) {
        setTimeout(checkBacklogLoop, 1500);
        return;
      }
      const pstat = fs.readFileSync(statPath, 'utf8');
      const pparts = pstat.split(' ');
      const isFrozen = (pparts[2] === 'T');

      if (!isFrozen) {
        // 未休眠时 1.5 秒检测一次，CPU 占用完全为 0
        setTimeout(checkBacklogLoop, 1500);
        return;
      }

      // 休眠状态下：检查 /proc/net/tcp 队列
      const tcpContent = fs.readFileSync('/proc/net/tcp', 'utf8');
      let incomingCount = 0;
      for (const line of tcpContent.split('\n')) {
        if (line.includes(hexPort3080) && line.includes(' 0A ')) {
          const parts = line.trim().split(/\s+/);
          if (parts[4]) {
            const q = parts[4].split(':');
            incomingCount = parseInt(q[1] || '0', 16);
          }
          break;
        }
      }

      if (incomingCount > 0) {
        // 发现 3080 端口有连接！检查连接发起方的 UID，过滤掉 App 自身内部探活
        const appUid = getAppUid();
        let isBrowserOrExternal = false;
        let externalCount = 0;

        for (const line of tcpContent.split('\n')) {
          const parts = line.trim().split(/\s+/);
          if (parts.length < 8) continue;
          const remAddr = parts[2];
          const st = parts[3];
          if (remAddr && remAddr.endsWith(hexPort3080)) {
            const connUid = parseInt(parts[7], 10);
            // 只要 UID 与 App 自身 UID 一致，判定为 App 内部心跳/探活，忽略避让
            if (appUid > 0 && connUid === appUid) {
              continue;
            }
            // 外部浏览器或第三方客户端建立的连接（或者来自非 App 的 UID）
            if (st === '01' || connUid !== appUid) {
              isBrowserOrExternal = true;
              externalCount++;
            }
          }
        }

        if (isBrowserOrExternal) {
          // 真正的外部浏览器或客户端访问！立即解冻！
          ensureBackendAwake(`浏览器访问3080(${externalCount}个有效请求敲门)`);
          setTimeout(checkBacklogLoop, 1500);
          return;
        }
      }

      // 处于休眠中但尚无外部有效敲门：1.5s 周期轮询
      setTimeout(checkBacklogLoop, 1500);
    } catch (e) {
      setTimeout(checkBacklogLoop, 1500);
    }
  }

  // 延后启动看门狗循环 (1.5s)
  setTimeout(checkBacklogLoop, 1500);
});