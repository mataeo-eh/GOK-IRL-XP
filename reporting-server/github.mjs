/** GitHub boundary: allowlisted API host, no redirects, no sensitive response logging. */
import { issueBody } from './reports.mjs';

/** Bound trusted-host responses too, and release every fetch body on all paths. */
async function readJson(response, expectedStatus) {
  if (response.status !== expectedStatus) {
    await response.body?.cancel();
    throw new Error('GITHUB_RESPONSE_REJECTED');
  }
  if (!response.body) throw new Error('GITHUB_RESPONSE_EMPTY');
  const reader = response.body.getReader();
  let size = 0;
  const chunks = [];
  try {
    // Fetch streams return {done, value}; value is bytes only while done is false.
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > 262144) throw new Error('GITHUB_RESPONSE_TOO_LARGE');
      chunks.push(value);
    }
    return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(Buffer.concat(chunks)));
  } finally {
    await reader.cancel();
    reader.releaseLock();
  }
}

export class GitHub {
  constructor(repository, token, fetcher = fetch) {
    const parts = typeof repository === 'string' ? repository.split('/') : [];
    const allowed = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._';
    if (parts.length !== 2 || parts[0] !== 'mataeo-eh' || !parts[1]
      || !Array.from(parts[1]).every(c => allowed.includes(c)) || !token) throw new Error('GITHUB_CONFIGURATION_REQUIRED');
    this.repository = repository; this.token = token; this.fetcher = fetcher;
  }
  async request(path = '', options = {}) {
    return this.fetcher(`https://api.github.com/repos/${this.repository}${path}`, {
      ...options, redirect: 'error', signal: AbortSignal.timeout(15000),
      headers: { Accept: 'application/vnd.github+json', Authorization: `Bearer ${this.token}`,
        'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'IRL-XP-Report-Receiver', 'Content-Type': 'application/json' }
    });
  }
  async verify() {
    const response = await this.request();
    const repository = await readJson(response, 200);
    // GET repository returns many fields. Only these exact privacy, ownership, and
    // issue-availability fields affect readiness; all other fields are ignored.
    if (!repository || repository.private !== true || repository.has_issues !== true
      || repository.archived !== false || repository.full_name !== this.repository)
      throw new Error('DESTINATION_NOT_PRIVATE_OR_UNAVAILABLE');
  }
  async create(report) {
    const response = await this.request('/issues', { method: 'POST', body: JSON.stringify({
      // No user text in the issue title: even title mention behavior cannot notify strangers.
      title: `[IRL XP] ${report.category} — ${report.submissionId}`, body: issueBody(report)
    }) });
    const issue = await readJson(response, 201);
    // Only positive integer number is retained. URLs, author, labels, body, and all
    // other issue response fields are deliberately ignored and never returned publicly.
    if (!issue || !Number.isSafeInteger(issue.number) || issue.number < 1) throw new Error('DELIVERY_UNCERTAIN');
    return issue.number;
  }
  async verifyIssue(number, report) {
    const response = await this.request(`/issues/${number}`);
    const issue = await readJson(response, 200);
    // Reconciliation consumes number/body and excludes pull requests. Other fields ignored.
    if (issue?.number !== number || issue.body !== issueBody(report) || Object.hasOwn(issue, 'pull_request'))
      throw new Error('RECONCILIATION_FAILED');
  }
}

/** One serial worker. A committed sending state exists before the side effect. */
export async function deliverOne(inbox, github) {
  const row = inbox.next();
  if (!row) return false;
  await github.verify(); // Safe GET retry; never send to a repository that became public.
  if (!inbox.sending(row.id)) return false;
  try { inbox.delivered(row.id, await github.create(JSON.parse(row.payload))); }
  catch { inbox.uncertain(row.id); }
  return true;
}
