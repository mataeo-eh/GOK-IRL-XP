/** Anonymous, bounded HTTP intake. Acceptance means durable queueing, not GitHub delivery. */
import { createServer } from 'node:http';
import { pathToFileURL } from 'node:url';
import { mkdirSync, existsSync } from 'node:fs';
import { dirname } from 'node:path';
import { Inbox, MAX_BODY_BYTES, ReportError } from './reports.mjs';
import { GitHub, deliverOne } from './github.mjs';

function reply(response, status, body) {
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', Connection: 'close',
    ...(status === 429 ? { 'Retry-After': '3600' } : {}) });
  response.end(JSON.stringify(body));
}
export function createReceiver(inbox, { available = () => true } = {}) {
  const server = createServer({ requestTimeout: 15000, headersTimeout: 10000, maxHeaderSize: 8192 }, (request, response) => {
    if (request.method === 'GET' && request.url === '/healthz') {
      reply(response, available() ? 200 : 503, { status: available() ? 'ready' : 'unavailable' }); return;
    }
    if (request.method !== 'POST' || request.url !== '/v1/reports') {
      reply(response, 404, { code: 'NOT_FOUND' }); return;
    }
    if (!available()) { reply(response, 503, { code: 'SERVICE_UNAVAILABLE' }); return; }
    const contentType = request.headers['content-type'];
    if (typeof contentType !== 'string' || !['application/json', 'application/json; charset=utf-8'].includes(contentType.toLowerCase())
      || request.headers['content-encoding'] !== undefined) {
      reply(response, 415, { code: 'UNSUPPORTED_MEDIA_TYPE' }); return;
    }
    // Stream counting enforces the limit even without Content-Length. Oversize bodies
    // are never buffered further; Connection: close bounds discarded input lifetime.
    let size = 0; let rejected = false; const chunks = [];
    request.on('data', chunk => {
      if (rejected) return;
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        rejected = true; chunks.length = 0; reply(response, 413, { code: 'REPORT_TOO_LARGE' });
      } else chunks.push(chunk);
    });
    request.on('error', () => { if (!response.headersSent) reply(response, 400, { code: 'INVALID_REPORT' }); });
    request.on('end', () => {
      if (rejected) return;
      if (!available()) { reply(response, 503, { code: 'SERVICE_UNAVAILABLE' }); return; }
      try {
        let value;
        try { value = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks))); }
        catch { throw new ReportError(400, 'INVALID_REPORT'); }
        reply(response, 202, inbox.accept(value));
      } catch (error) {
        reply(response, error instanceof ReportError ? error.status : 503,
          { code: error instanceof ReportError ? error.code : 'SERVICE_UNAVAILABLE' });
      }
    });
  });
  server.maxConnections = 100;
  server.maxRequestsPerSocket = 1;
  return server;
}

export async function start(env = process.env) {
  const path = env.DATABASE_PATH || '/data/reports.sqlite';
  mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
  const inbox = new Inbox(path);
  const github = new GitHub(env.GITHUB_REPOSITORY, env.GITHUB_TOKEN);
  let ready = false; let stopping = false; let active;
  const enabled = () => env.REPORTS_ENABLED === 'true' && !stopping
    && !existsSync(`${path}.disabled`);
  const server = createReceiver(inbox, { available: () => enabled() && ready });
  // Listen before verification so health checks describe startup failures. Safe GETs
  // recover automatically after outages/token fixes; intake stays closed meanwhile.
  const port = Number(env.PORT || '3000');
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('INVALID_PORT');
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, '0.0.0.0', resolve); });
  async function tick() {
    try {
      // Retention must continue during GitHub outages or an intake kill switch.
      inbox.maintain();
      if (!enabled()) { ready = false; return; }
      await github.verify(); ready = true;
      await deliverOne(inbox, github);
    } catch { ready = false; console.error('REPORT_RECEIVER_UNAVAILABLE'); }
  }
  active = tick();
  const timer = setInterval(() => {
    // Chain work instead of overlapping a slow GitHub request with another worker.
    if (!active) active = tick().finally(() => { active = undefined; });
  }, 10000);
  active.finally(() => { active = undefined; });
  console.log('REPORT_RECEIVER_LISTENING');
  async function stop() {
    if (stopping) return;
    stopping = true; clearInterval(timer);
    server.close(); server.closeAllConnections();
    await active; inbox.close();
  }
  process.once('SIGTERM', stop); process.once('SIGINT', stop);
  return { server, inbox, stop };
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  start().catch(() => { console.error('REPORT_RECEIVER_START_FAILED'); process.exitCode = 1; });
}
