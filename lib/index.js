import fs from 'node:fs';

export const name = 'dsh-xiaomi-asr';

function getBridgeToken() {
  try {
    return fs.readFileSync('/root/.dsh/.bridge_token', 'utf8').trim();
  } catch (_e) {
    return '';
  }
}

export function apply(ctx) {
  ctx.inject(['webServer'], (webCtx) => {
    webCtx.effect(() => {
      return webCtx.webServer.register({
        kind: 'prefix',
        path: '/api/xiaomi-asr',
        handler: async (req, res) => {
          try {
            const urlObj = new URL(req.url, 'http://127.0.0.1:3080');
            const subPath = urlObj.pathname.replace(/^\/api\/xiaomi-asr\/?/, '');
            const action = subPath.split('/')[0] || 'status';

            const token = getBridgeToken();
            const targetUrl = new URL(`http://127.0.0.1:3095/app/asr/${action}`);
            if (token) targetUrl.searchParams.set('token', token);

            for (const [k, v] of urlObj.searchParams.entries()) {
              if (k !== 'token') targetUrl.searchParams.set(k, v);
            }

            const response = await fetch(targetUrl.toString(), {
              method: 'GET',
              headers: { 'Accept': 'application/json' },
              signal: AbortSignal.timeout(15000)
            });

            const text = await response.text();
            let parsed = null;
            try {
              parsed = JSON.parse(text);
              if (parsed && typeof parsed.result === 'string') {
                try {
                  parsed = JSON.parse(parsed.result);
                } catch (_) {
                  parsed = { result: parsed.result };
                }
              }
            } catch (_) {
              parsed = { raw: text };
            }

            const resPayload = JSON.stringify(parsed);
            res.writeHead(200, {
              'Content-Type': 'application/json; charset=utf-8',
              'Content-Length': Buffer.byteLength(resPayload),
              'Access-Control-Allow-Origin': '*'
            });
            res.end(resPayload);
          } catch (err) {
            const errPayload = JSON.stringify({ error: String(err) });
            res.writeHead(500, {
              'Content-Type': 'application/json; charset=utf-8',
              'Content-Length': Buffer.byteLength(errPayload)
            });
            res.end(errPayload);
          }
        }
      });
    });
  });
}
