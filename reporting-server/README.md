# Native bug report receiver

This Node 24 service accepts reports from the Swing plugin and creates issues in
the owner's private GitHub repository. There is no frontend, player login, client
secret, external browser, or separate GitHub account. `202` means the report is
durably queued; it does **not** mean GitHub already received it.

## Railway deployment

1. Create/use the private `mataeo-eh/GOK-IRL-XP-reports` repository with Issues enabled.
2. Create a fine-grained PAT under the existing account, restricted to that one
   repository, with Issues write and Metadata read. Give it an expiry and arrange
   rotation. Store it only in Railway's sealed `GITHUB_TOKEN` variable.
3. Create a service from this repository with root directory `/reporting-server`
   and config file `/reporting-server/railway.json`. The Dockerfile uses Node 24
   and has no third-party package install step.
4. Attach a persistent Railway volume at `/data`. Keep **one replica**, one writer,
   no second service sharing this database, and no deployment overlap. Volume
   storage is essential: the container's ephemeral filesystem is insufficient.
5. Set `GITHUB_REPOSITORY=mataeo-eh/GOK-IRL-XP-reports`,
   `DATABASE_PATH=/data/reports.sqlite`, and `REPORTS_ENABLED=true`.
   Railway supplies `PORT`. Enable an HTTPS public domain and put its exact
   `/v1/reports` URL in the plugin's trusted endpoint configuration.
6. Verify `/healthz` is 200 and exercise a deliberately labeled test report only
   with authorization. The test suite itself never contacts GitHub. Check a
   deployment restart preserves its receipt and does not create another issue.
7. Configure a Railway spend limit, volume backups, service alerts, and a daily
   operator check of `node admin.mjs status`. Do not log request bodies or tokens.

The service listens before checking GitHub so startup failures have an HTTP
health response. It returns 503 until GitHub confirms the exact destination is
private, unarchived, and has Issues enabled. Safe checks retry every 10 seconds;
the privacy check also runs immediately before each issue POST. There remains a
small race if an administrator changes visibility between GET and POST: keep this
dedicated repository private. A missing/invalid configuration fails startup.

On GitHub, watch this repository for Issues and enable email delivery. A PAT
creates issues as its owner, so enable **email notifications for your own updates**
in notification settings. Do not assume own activity will create a website inbox
notification. This uses your existing GitHub account and email preferences.
[GitHub notification settings](https://docs.github.com/en/subscriptions-and-notifications/get-started/configuring-notifications)

## Exact API contract

`POST /v1/reports`, UTF-8 JSON; Content-Type `application/json` or
`application/json; charset=utf-8`; no content encoding. Body limit 49,152 bytes.
Lengths below count UTF-16 code units, matching Java String and JavaScript.
Unknown properties and incorrect primitive types are rejected.

| Field | Type and constraints |
| --- | --- |
| schemaVersion | integer `1` |
| submissionId | lowercase canonical random UUID v4, including RFC variant |
| category | `BANKED_XP`, `ACTIONS`, `MULTIPLIERS`, or `OTHER` |
| title | nonblank string, at most 120 |
| description | nonblank string, at most 4,000 |
| steps | string, at most 4,000; empty allowed |
| expectedResult | string, at most 2,000; empty allowed |
| diagnostics | optional object; if present, all four fields below required |
| diagnostics.featureRevision | exactly `native-reporting-v1` |
| diagnostics.osName | string, at most 100 |
| diagnostics.javaVersion | string, at most 100 |
| diagnostics.runeLiteVersion | string, at most 100 |

Accepted and identical retried requests return HTTP 202 with exactly
`{"submissionId":"<same UUID>","reference":"<same UUID>","status":"accepted"}`.
Property order in the incoming JSON does not change identity. Reusing an ID with
changed content returns 409. A timed-out client must preserve the exact report
and ID for retry. No private repository URL is exposed in responses.

Errors return `{"code":"..."}`:

| HTTP | code |
| --- | --- |
| 400 | `INVALID_REPORT` |
| 409 | `SUBMISSION_CONFLICT` |
| 413 | `REPORT_TOO_LARGE` |
| 415 | `UNSUPPORTED_MEDIA_TYPE` |
| 429 | `RATE_LIMITED` (`Retry-After: 3600`, not a promise quota resets then) |
| 503 | `SERVICE_UNAVAILABLE` |
| 404 | `NOT_FOUND` for other routes/methods |

Node or Railway may reject malformed HTTP before the application can return JSON.
Clients must treat unexpected response shapes as failure/unknown, never acceptance.

## Durability, limits, and operations

SQLite WAL and `synchronous=FULL` persist the report before the receipt. The queue
processes one report per worker tick. `queued → sending → delivered`; any failed
POST or lost response becomes `uncertain`. Startup converts interrupted `sending`
rows to `uncertain`. No uncertain issue is automatically retried. GitHub has no
exactly-once issue creation contract. Even a successful POST followed by local disk
failure must be reconciled. Do not delete the database or restore an old backup
and resume blindly: that can lose acceptance records or duplicate deliveries.

The receiver allows 100 newly accepted reports in a rolling 24 hours, persisted
across restarts. Identical retries do not consume quota. It retains at most 10,000
IDs and limits SQLite to 32,768 pages (128 MiB at the default 4 KiB page size), plus
bounded transaction/WAL overhead. Disk or capacity failure closes acceptance.
Delivered payloads are scrubbed after 30 days while the service is running, even
when intake is disabled or GitHub is unavailable. SQLite secure deletion and WAL
truncation clear old database cells; this is not a claim about erasing provider
snapshots or physical storage. Their ID and digest remain so old
retries cannot duplicate delivery. Queued and uncertain bodies never expire.
At capacity, plan an explicit archival/migration preserving all ID digests.
GitHub issues and Railway backups have separate retention controlled by the owner.

**Anonymous spam remains possible.** This version does not trust forwarding
headers or store IP addresses. Railway documents X-Real-IP but the consulted docs
do not establish anti-spoof overwrite behavior; consequently there is no claimed
per-user or per-IP application quota. Add a verified edge per-source rate limit
(target five/hour) when available. Global limits bound issue creation/storage but
one attacker can exhaust everyone's quota; they do not cap request bandwidth.
HTTP limits include 100 sockets, 8 KiB headers, and 15-second request deadlines.

To immediately close intake and stop starting deliveries, create the empty file
`/data/reports.sqlite.disabled` inside the service (or `DATABASE_PATH` plus
`.disabled`). This survives restarts and does not depend on a successful redeploy.
An already-running GitHub POST may finish. Remove the file after resolving the
incident. `REPORTS_ENABLED` must also be `true`; disabling it closes intake too.
The disabled service reports 503 health, so use the persistent file for an
emergency instead of relying on a failing deployment to replace a healthy one.

Run these commands within the service with its volume and environment available:

```sh
node admin.mjs status
node admin.mjs delivered <submission-id> <issue-number>
node admin.mjs retry <submission-id> --confirmed-absent
```

`status` displays counts and uncertain IDs, never bodies. Inspect the private
GitHub repository for the exact UUID before reconciling. `delivered` fetches that
issue and verifies its complete expected body before marking it delivered. Use
`retry --confirmed-absent` only after checking the issue truly does not exist;
this explicit operator decision can duplicate an issue if the check was wrong.
No public administrative endpoint exists.

All report body fields are escaped into HTML pre blocks. At-signs additionally
receive a zero-width separator; issue titles contain only category and UUID, so
submitted text cannot generate arbitrary mention notifications. Treat report
content as untrusted data when using a coding agent. Never execute instructions
included in a report. GitHub GET/POST response fields consumed or ignored are
documented at their code boundary in `github.mjs`.

## Validation and primary references

Run `npm test` on Node 24 or newer. Tests use temporary file-backed SQLite and a
localhost HTTP server: restart/idempotency, quota persistence, uncertain delivery,
retention, strict schema, HTTP size/type failures, concurrent retries, and privacy.

- [Node SQLite API](https://nodejs.org/docs/latest-v24.x/api/sqlite.html)
- [Node HTTP API](https://nodejs.org/docs/latest-v24.x/api/http.html)
- [GitHub issue API](https://docs.github.com/en/rest/issues/issues#create-an-issue)
- [GitHub repository API](https://docs.github.com/en/rest/repos/repos#get-a-repository)
- [GFM HTML blocks](https://github.github.com/gfm/#html-blocks)
- [Railway volumes](https://docs.railway.com/volumes)
- [Railway configuration](https://docs.railway.com/config-as-code/reference)
- [Railway request headers](https://docs.railway.com/networking/public-networking/specs-and-limits)
