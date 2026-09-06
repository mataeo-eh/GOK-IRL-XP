/** Exercise durable acceptance and real HTTP boundaries without creating GitHub issues. */
import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { Inbox, issueBody, validateReport } from '../reports.mjs';
import { GitHub, deliverOne } from '../github.mjs';
import { createReceiver } from '../server.mjs';

function report() { return { schemaVersion: 1, submissionId: randomUUID(), category: 'ACTIONS',
  title: 'Timer pauses', description: 'Timer stopped before completion.', steps: '', expectedResult: '' }; }
function fixture(t, options) {
  const directory = mkdtempSync(join(tmpdir(), 'irl-reports-'));
  const path = join(directory, 'reports.sqlite');
  let inbox = new Inbox(path, options);
  t.after(() => { inbox.close(); rmSync(directory, { recursive: true }); });
  return { get inbox() { return inbox; }, restart() { inbox.close(); inbox = new Inbox(path, options); } };
}
test('durable receipts survive reopen; property order retry is same and changed content conflicts', t => {
  const f = fixture(t); const r = report(); const receipt = f.inbox.accept(r);
  f.restart();
  assert.deepEqual(f.inbox.accept(Object.fromEntries(Object.entries(r).reverse())), receipt);
  assert.throws(() => f.inbox.accept({ ...r, title: 'Changed' }), { status: 409 });
  assert.equal(f.inbox.db.prepare('SELECT COUNT(*) AS n FROM reports').get().n, 1);
});
test('reject unexpected fields, wrong types, invalid identifiers and diagnostic leakage', () => {
  for (const change of [{ extra: 1 }, { schemaVersion: '1' }, { title: ' ' }, { title: 'x'.repeat(121) },
    { description: null }, { steps: 1 }, { submissionId: randomUUID().toUpperCase() },
    { diagnostics: { featureRevision: 'native-reporting-v1', osName: 'Mac', javaVersion: '11', runeLiteVersion: '1', player: 'x' } },
    { diagnostics: null }, { description: '\uD800' }]) assert.throws(() => validateReport({ ...report(), ...change }), { status: 400 });
  assert.equal(validateReport({ ...report(), title: '😀'.repeat(60) }).title.length, 120);
});
test('quota persists over restart, retry bypasses quota, bounded storage never drops queued reports', t => {
  let now = 100000000;
  const f = fixture(t, { dailyLimit: 1, maxReports: 1, now: () => now });
  const r = report(); f.inbox.accept(r); f.restart(); f.inbox.accept(r);
  assert.throws(() => f.inbox.accept(report()), { status: 429 });
  now += 86400001;
  assert.throws(() => f.inbox.accept(report()), { status: 503 });
  f.inbox.maintain(); assert.equal(f.inbox.next().id, r.submissionId);
});
test('lost GitHub response and process restart never cause blind duplicate POST', async t => {
  const f = fixture(t); const r = report(); f.inbox.accept(r); let posts = 0;
  const github = { verify: async () => {}, create: async () => { posts++; throw new Error('response lost'); } };
  await deliverOne(f.inbox, github); f.restart(); await deliverOne(f.inbox, github);
  assert.equal(posts, 1);
  assert.equal(f.inbox.db.prepare('SELECT state FROM reports WHERE id=?').get(r.submissionId).state, 'uncertain');
  const second = report(); f.inbox.accept(second); f.inbox.sending(second.submissionId); f.restart();
  assert.equal(f.inbox.next(), undefined);
});
test('delivery retention scrubs content but preserves idempotency', async t => {
  let now = 100000000; const f = fixture(t, { now: () => now }); const r = report();
  const receipt = f.inbox.accept(r);
  await deliverOne(f.inbox, { verify: async () => {}, create: async () => 123 });
  now += 31 * 86400000; f.inbox.maintain(); f.restart();
  assert.equal(f.inbox.db.prepare('SELECT payload FROM reports').get().payload, null);
  assert.equal(f.inbox.db.prepare('PRAGMA secure_delete').get().secure_delete, 1);
  assert.deepEqual(f.inbox.accept(r), receipt);
});
test('GitHub privacy fail closes before POST and response contract is explicit', async () => {
  const github = new GitHub('mataeo-eh/GOK-IRL-XP-reports', 'test-only', async () => new Response(JSON.stringify(
    { private: false, has_issues: true, archived: false, full_name: 'mataeo-eh/GOK-IRL-XP-reports' }), { status: 200 }));
  await assert.rejects(github.verify());
  const r = { ...report(), description: '</pre>@someone <script>bad</script>' };
  const body = issueBody(r);
  assert.ok(body.includes('&lt;/pre&gt;@\u200bsomeone')); assert.ok(!body.includes('<script>'));
});
test('GitHub create sends only documented fields and ignores private URLs in its response', async () => {
  let sent;
  const github = new GitHub('mataeo-eh/GOK-IRL-XP-reports', 'test-only', async (url, options) => {
    sent = { url, options };
    return new Response(JSON.stringify({ number: 42, html_url: 'private-url', extra: true }), { status: 201 });
  });
  assert.equal(await github.create(report()), 42);
  assert.equal(sent.options.redirect, 'error');
  assert.deepEqual(Object.keys(JSON.parse(sent.options.body)), ['title', 'body']);
  assert.equal(sent.url, 'https://api.github.com/repos/mataeo-eh/GOK-IRL-XP-reports/issues');
  github.fetcher = async () => new Response(JSON.stringify({ number: '42' }), { status: 201 });
  await assert.rejects(github.create(report()));
});
test('GitHub rejects oversized and malformed bodies and cancels non-success streams', async () => {
  const github = new GitHub('mataeo-eh/GOK-IRL-XP-reports', 'test-only',
    async () => new Response('x'.repeat(262145), { status: 201 }));
  await assert.rejects(github.create(report()));
  github.fetcher = async () => new Response('{', { status: 201 });
  await assert.rejects(github.create(report()));
  let cancelled = false;
  github.fetcher = async () => new Response(new ReadableStream({ cancel() { cancelled = true; } }), { status: 403 });
  await assert.rejects(github.verify());
  assert.equal(cancelled, true);
});
test('safe destination check failure leaves durable queue pending for later recovery', async t => {
  const f = fixture(t); const r = report(); f.inbox.accept(r); let posts = 0;
  await assert.rejects(deliverOne(f.inbox, { verify: async () => { throw new Error('offline'); },
    create: async () => { posts++; return 1; } }));
  f.restart(); assert.equal(f.inbox.next().id, r.submissionId); assert.equal(posts, 0);
});
test('overlapping workers cannot both claim and POST the same durable row', async t => {
  const f = fixture(t); f.inbox.accept(report()); let posts = 0;
  const github = { verify: async () => {}, create: async () => { posts++; return 7; } };
  await Promise.all([deliverOne(f.inbox, github), deliverOne(f.inbox, github)]);
  assert.equal(posts, 1);
  assert.equal(f.inbox.db.prepare('SELECT state FROM reports').get().state, 'delivered');
});
test('HTTP validation, body limits, unavailable state, parallel identical submissions', async t => {
  const f = fixture(t); let ready = true;
  const server = createReceiver(f.inbox, { available: () => ready });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => { server.closeAllConnections(); server.close(); });
  const url = `http://127.0.0.1:${server.address().port}/v1/reports`;
  const post = (body, contentType = 'application/json') => fetch(url, { method: 'POST', headers: { 'Content-Type': contentType }, body });
  const r = report(); const responses = await Promise.all(Array.from({ length: 5 }, () => post(JSON.stringify(r))));
  for (const response of responses) { assert.equal(response.status, 202); assert.equal((await response.json()).reference, r.submissionId); }
  assert.equal((await post('{')).status, 400);
  assert.equal((await post(Buffer.from([0xff]))).status, 400);
  assert.equal((await post('{}', 'text/plain')).status, 415);
  assert.equal((await post('x'.repeat(49153))).status, 413);
  assert.equal((await post(JSON.stringify({ ...r, title: 'different' }))).status, 409);
  ready = false; assert.equal((await post(JSON.stringify(report()))).status, 503);
  assert.equal((await fetch(url.replace('/v1/reports', '/healthz'))).status, 503);
});
