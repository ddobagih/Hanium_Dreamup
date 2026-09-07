import http from 'node:http';
import { isIP } from 'node:net';
import { pathToFileURL } from 'node:url';

const MAX_BODY_BYTES = 10 * 1024 * 1024;
const RESPONSE_HEADERS = {
  'cache-control': 'no-store',
  pragma: 'no-cache',
  'x-content-type-options': 'nosniff',
};
const HOP_HEADERS = [
  'connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization',
  'proxy-connection', 'te', 'trailer', 'transfer-encoding', 'upgrade',
];
const CLIENT_IP_HEADERS = [
  'forwarded', 'x-forwarded-for', 'x-forwarded-host', 'x-real-ip',
  'client-ip', 'x-client-ip', 'true-client-ip', 'cf-connecting-ip',
  'cf-connecting-ipv6', 'cf-pseudo-ipv4', 'cf-visitor',
];

function endJson(response, status, error) {
  if (response.headersSent || response.destroyed) return;
  response.writeHead(status, {
    ...RESPONSE_HEADERS,
    'content-type': 'application/json; charset=utf-8',
  });
  response.end(JSON.stringify({ error }));
}

function allowedPath(rawUrl, method) {
  if (!['GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS'].includes(method)) return false;
  if (!rawUrl?.startsWith('/') || rawUrl.startsWith('//') || rawUrl.includes('#')) return false;
  const path = rawUrl.split('?')[0];
  // App endpoint paths use ASCII identifiers; encoded query values remain untouched.
  if (/[\\%]/.test(path)) return false;
  if (new URL(rawUrl, 'http://127.0.0.1').pathname !== path) return false;
  if (/^\/api\/(?:internal|admin|debug|dev)(?:\/|$)/.test(path)) return false;
  if (path.startsWith('/api/') || path === '/privacy/rights') return true;
  if (path === '/privacy/account-deletions') return method === 'POST';
  if (/^\/privacy\/account-deletions\/[A-Za-z0-9_-]{16,128}\/status$/.test(path)) return method === 'GET';
  return method === 'POST' && /^\/privacy\/account-deletions\/[A-Za-z0-9_-]{16,128}\/device-evidence$/.test(path);
}

function stripHopHeaders(source) {
  const headers = { ...source };
  const connectionHeaders = String(headers.connection || '').split(',').map(name => name.trim().toLowerCase());
  for (const name of [...HOP_HEADERS, ...connectionHeaders]) delete headers[name];
  return headers;
}

export function createTunnelProxy({ upstreamPort = 8081 } = {}) {
  const server = http.createServer((request, response) => {
    if (!allowedPath(request.url, request.method)) return endJson(response, 404, 'not_found');
    // Only cloudflared reaches this loopback listener. Never trust client-supplied forwarding headers.
    const clientIp = request.headers['cf-connecting-ip'];
    if (typeof clientIp !== 'string' || !isIP(clientIp)) return endJson(response, 403, 'invalid_tunnel_client');
    const contentLength = request.headers['content-length'];
    if (contentLength !== undefined && (!/^\d+$/.test(contentLength) || Number(contentLength) > MAX_BODY_BYTES)) {
      return endJson(response, 413, 'payload_too_large');
    }
    const headers = stripHopHeaders(request.headers);
    for (const name of CLIENT_IP_HEADERS) delete headers[name];
    headers.host = `127.0.0.1:${upstreamPort}`;
    headers['x-real-ip'] = clientIp;
    headers['x-forwarded-proto'] = 'https';
    const upstream = http.request({
      hostname: '127.0.0.1', port: upstreamPort,
      path: request.url, method: request.method, headers,
      timeout: 120_000,
    }, upstreamResponse => {
      if (response.destroyed || response.writableEnded) return upstreamResponse.destroy();
      response.writeHead(upstreamResponse.statusCode || 502, {
        ...stripHopHeaders(upstreamResponse.headers), ...RESPONSE_HEADERS,
      });
      upstreamResponse.on('error', () => response.destroy());
      upstreamResponse.pipe(response);
    });
    upstream.on('timeout', () => upstream.destroy());
    upstream.on('error', () => endJson(response, 502, 'gateway_unavailable'));
    request.on('aborted', () => upstream.destroy());
    response.on('close', () => {
      if (!response.writableFinished) upstream.destroy();
    });
    let receivedBytes = 0;
    request.on('data', chunk => {
      receivedBytes += chunk.length;
      if (receivedBytes <= MAX_BODY_BYTES) return;
      request.unpipe(upstream);
      endJson(response, 413, 'payload_too_large');
      upstream.destroy();
      request.resume();
    });
    request.pipe(upstream);
  });
  server.on('upgrade', (_request, socket) => {
    socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\nCache-Control: no-store\r\nPragma: no-cache\r\nX-Content-Type-Options: nosniff\r\nContent-Length: 0\r\n\r\n');
  });
  server.on('connect', (_request, socket) => socket.destroy());
  server.headersTimeout = 10_000;
  server.requestTimeout = 30_000;
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const server = createTunnelProxy();
  server.listen(8082, '127.0.0.1', () => {
    process.stdout.write('Visual-test tunnel proxy listening on 127.0.0.1:8082\n');
  });
  for (const signal of ['SIGINT', 'SIGTERM']) {
    process.once(signal, () => server.close(() => process.exit(0)));
  }
}
