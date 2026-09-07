import test from 'node:test';
import http from 'node:http';
import { createTunnelProxy } from './visual-test-tunnel-proxy.mjs';

const CF_IP = '203.0.113.10';

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function equal(actual, expected, message = 'Unexpected value') {
  assert(JSON.stringify(actual) === JSON.stringify(expected),
    `${message}: expected ${JSON.stringify(expected)}, received ${JSON.stringify(actual)}`);
}

function securityHeaders(response) {
  equal(response.headers['cache-control'], 'no-store', 'Cache-Control');
  equal(response.headers.pragma, 'no-cache', 'Pragma');
  equal(response.headers['x-content-type-options'], 'nosniff', 'X-Content-Type-Options');
}

function echo(req, res) {
  const chunks = [];
  req.on('data', chunk => chunks.push(chunk));
  req.on('end', () => {
    res.writeHead(200, {
      'content-type': 'application/json',
      'x-upstream-method': req.method,
    });
    res.end(JSON.stringify({
      method: req.method,
      url: req.url,
      headers: req.headers,
      body: Buffer.concat(chunks).toString('utf8'),
    }));
  });
}

function listen(server) {
  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      server.removeListener('error', reject);
      resolve(server.address().port);
    });
  });
}

function close(server) {
  return new Promise(resolve => {
    server.close(resolve);
    server.closeAllConnections?.();
  });
}

async function withProxy(t, handler = echo) {
  const upstream = http.createServer(handler);
  t.after(() => close(upstream));
  const upstreamPort = await listen(upstream);
  const proxy = createTunnelProxy({ upstreamPort });
  t.after(() => close(proxy));
  assert(proxy instanceof http.Server, 'createTunnelProxy must return an HTTP server');
  equal(proxy.listening, false, 'The returned server must not listen automatically');
  return { port: await listen(proxy), upstream, upstreamPort };
}

function request(port, { path = '/api/echo', method = 'GET', headers = {}, body } = {}) {
  const requestHeaders = { 'cf-connecting-ip': CF_IP, ...headers };
  for (const key of Object.keys(requestHeaders)) {
    if (requestHeaders[key] === undefined) delete requestHeaders[key];
  }
  return new Promise((resolve, reject) => {
    const req = http.request({
      hostname: '127.0.0.1', port, path, method, headers: requestHeaders, agent: false,
    }, res => {
      const chunks = [];
      res.on('data', chunk => chunks.push(chunk));
      res.on('error', reject);
      res.on('end', () => resolve({
        statusCode: res.statusCode,
        headers: res.headers,
        body: Buffer.concat(chunks).toString('utf8'),
      }));
    });
    req.on('error', reject);
    req.on('upgrade', (_res, socket) => {
      socket.destroy();
      reject(new Error('The proxy accepted a WebSocket upgrade'));
    });
    req.setTimeout(5000, () => req.destroy(new Error('Proxy request timed out')));
    req.end(body);
  });
}

test('forwards ordinary HTTP methods under /api/', async t => {
  const { port } = await withProxy(t);
  for (const method of ['GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS']) {
    const response = await request(port, { path: '/api/example', method });
    equal(response.statusCode, 200, method);
    equal(response.headers['x-upstream-method'], method, 'Forwarded method');
    securityHeaders(response);
    if (method === 'HEAD') equal(response.body, '', 'HEAD response body');
    else equal(JSON.parse(response.body).url, '/api/example', 'Forwarded API path');
  }
});

test('allows the public privacy routes and valid token length boundaries', async t => {
  const { port } = await withProxy(t);
  const routes = [['GET', '/privacy/rights'], ['POST', '/privacy/account-deletions']];
  for (const token of ['Aa0_-123456789xyz', 'a'.repeat(128)]) {
    routes.push(['GET', `/privacy/account-deletions/${token}/status`]);
    routes.push(['POST', `/privacy/account-deletions/${token}/device-evidence`]);
  }
  for (const [method, path] of routes) {
    const response = await request(port, { method, path });
    equal(response.statusCode, 200, `${method} ${path}`);
    equal(JSON.parse(response.body).url, path, 'Forwarded privacy path');
    securityHeaders(response);
  }
});

test('blocks private paths, traversal encodings, and unsupported privacy requests', async t => {
  let upstreamRequests = 0;
  const { port } = await withProxy(t, (req, res) => { upstreamRequests++; echo(req, res); });
  const token = 'a'.repeat(16);
  const routes = [
    ['GET', '/'], ['GET', '/internal/health'], ['GET', '/unknown'],
    ['GET', '/api/../internal/health'], ['GET', '/api/..\\internal\\health'],
    ['GET', '/api/%2e%2e/internal/health'], ['GET', '/api/%2E./internal/health'],
    ['GET', '/api/.%2e/internal/health'], ['GET', '/api/%2f..%2finternal%2fhealth'],
    ['GET', '/api/%5c..%5cinternal%5chealth'],
    ['GET', '/api/%252e%252e/internal/health'],
    ['GET', '/privacy/rights/extra'], ['GET', '/privacy/account-deletions'],
    ['POST', `/privacy/account-deletions/${token}/status`],
    ['GET', `/privacy/account-deletions/${token}/device-evidence`],
    ['GET', `/privacy/account-deletions/${'a'.repeat(15)}/status`],
    ['GET', `/privacy/account-deletions/${'a'.repeat(129)}/status`],
    ['GET', `/privacy/account-deletions/${'a'.repeat(15)}./status`],
  ];
  for (const [method, path] of routes) {
    const response = await request(port, { method, path });
    equal(response.statusCode, 404, `${method} ${path}`);
    securityHeaders(response);
  }
  equal(upstreamRequests, 0, 'Blocked paths must not reach upstream');
});

test('requires one valid CF-Connecting-IP on allowed routes', async t => {
  let upstreamRequests = 0;
  const { port } = await withProxy(t, (req, res) => { upstreamRequests++; echo(req, res); });
  for (const ip of [undefined, '', 'not-an-ip', '999.0.0.1', '203.0.113.10:443',
    '203.0.113.10, 198.51.100.5']) {
    const response = await request(port, { headers: { 'cf-connecting-ip': ip } });
    equal(response.statusCode, 403, `Invalid CF-Connecting-IP ${JSON.stringify(ip)}`);
    securityHeaders(response);
  }
  const privateResponse = await request(port, {
    path: '/internal/health', headers: { 'cf-connecting-ip': undefined },
  });
  equal(privateResponse.statusCode, 404, 'Private paths remain unavailable without CF headers');
  securityHeaders(privateResponse);
  equal(upstreamRequests, 0, 'Invalid IP requests must not reach upstream');
});

test('replaces trusted IP and scheme headers and removes spoofed forwarding headers', async t => {
  const { port } = await withProxy(t);
  const spoofed = {
    'x-real-ip': '198.51.100.7', 'x-forwarded-proto': 'http',
    forwarded: 'for=198.51.100.7;host=attacker.example;proto=http',
    'x-forwarded-for': '198.51.100.7', 'x-forwarded-host': 'attacker.example',
    'client-ip': '198.51.100.7', 'x-client-ip': '198.51.100.7',
    'true-client-ip': '198.51.100.7',
  };
  const response = await request(port, { headers: spoofed });
  equal(response.statusCode, 200);
  const forwarded = JSON.parse(response.body).headers;
  equal(forwarded['x-real-ip'], CF_IP, 'Trusted client IP');
  equal(forwarded['x-forwarded-proto'], 'https', 'Trusted scheme');
  for (const header of ['forwarded', 'x-forwarded-for', 'x-forwarded-host',
    'client-ip', 'x-client-ip', 'true-client-ip']) {
    equal(forwarded[header], undefined, `Spoofable header ${header}`);
  }
  securityHeaders(response);
});

test('preserves the request body, cookies, query string, and multiple Set-Cookie headers', async t => {
  const cookies = ['session=first; Path=/; HttpOnly; Secure', 'csrf=second; Path=/; Secure'];
  const { port } = await withProxy(t, (req, res) => {
    res.setHeader('set-cookie', cookies);
    echo(req, res);
  });
  const body = JSON.stringify({ message: 'body + / & =', nested: { enabled: true } });
  const path = '/api/echo?cursor=a%2Fb&value=one+two&value=three&literal=%2e%2e';
  const cookie = 'session=incoming; csrf=original';
  const response = await request(port, {
    method: 'POST', path, body,
    headers: { cookie, 'content-type': 'application/json' },
  });
  equal(response.statusCode, 200);
  const received = JSON.parse(response.body);
  equal(received.body, body, 'Request body');
  equal(received.url, path, 'Query string');
  equal(received.headers.cookie, cookie, 'Cookie header');
  equal(received.headers['content-type'], 'application/json', 'Content type');
  equal(response.headers['set-cookie'], cookies, 'Set-Cookie headers');
  securityHeaders(response);
});

test('overrides unsafe upstream caching and content sniffing headers', async t => {
  const { port } = await withProxy(t, (_req, res) => {
    res.writeHead(201, {
      'cache-control': 'public, max-age=86400', pragma: 'cache',
      'x-content-type-options': 'sniff', 'content-type': 'text/plain',
    });
    res.end('upstream response');
  });
  const response = await request(port);
  equal(response.statusCode, 201, 'Upstream status');
  equal(response.body, 'upstream response', 'Upstream body');
  securityHeaders(response);
});

test('rejects WebSocket upgrades without forwarding them', async t => {
  let upstreamRequests = 0;
  let upstreamUpgrades = 0;
  const { port, upstream } = await withProxy(t, (req, res) => {
    upstreamRequests++;
    echo(req, res);
  });
  upstream.on('upgrade', (_req, socket) => {
    upstreamUpgrades++;
    socket.end('HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n');
  });
  const response = await request(port, {
    headers: { connection: 'Upgrade', upgrade: 'websocket' },
  }).catch(error => {
    if (error.code === 'ECONNRESET') return null;
    throw error;
  });
  if (response) {
    assert(response.statusCode >= 400 && response.statusCode < 500, 'Upgrade must be rejected');
    securityHeaders(response);
  }
  equal(upstreamRequests, 0, 'Upgrade must not become an upstream HTTP request');
  equal(upstreamUpgrades, 0, 'Upgrade must not reach the upstream socket');
});

test('rejects a declared request body above 10 MiB before contacting upstream', async t => {
  let upstreamRequests = 0;
  const { port } = await withProxy(t, (req, res) => { upstreamRequests++; echo(req, res); });
  const response = await request(port, {
    method: 'POST', headers: { 'content-length': String(10 * 1024 * 1024 + 1) },
  });
  equal(response.statusCode, 413, 'Oversized body');
  equal(upstreamRequests, 0, 'Oversized body must not reach upstream');
  securityHeaders(response);
});

test('returns a sanitized JSON 502 when upstream disconnects', async t => {
  const { port, upstreamPort } = await withProxy(t, (req, _res) => {
    req.resume();
    req.socket.destroy();
  });
  const response = await request(port, {
    method: 'POST', path: '/api/private?token=query-secret-marker',
    body: JSON.stringify({ secret: 'body-secret-marker' }),
    headers: { authorization: 'Bearer auth-secret-marker', 'content-type': 'application/json' },
  });
  equal(response.statusCode, 502, 'Upstream failure');
  assert(/^application\/json(?:;|$)/i.test(response.headers['content-type'] ?? ''),
    'Gateway errors must use JSON content type');
  const errorBody = JSON.parse(response.body);
  assert(errorBody !== null && typeof errorBody === 'object' && !Array.isArray(errorBody),
    'Gateway error body must be a JSON object');
  for (const secret of ['127.0.0.1', `:${upstreamPort}`, '/api/private',
    'query-secret-marker', 'body-secret-marker', 'auth-secret-marker']) {
    assert(!response.body.includes(secret), `Gateway error leaked ${secret}`);
  }
  securityHeaders(response);
});
