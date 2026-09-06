/** Operator-only local CLI. Never exposed through HTTP; never prints report content. */
import { Inbox, validId } from './reports.mjs';
import { GitHub } from './github.mjs';

const [command, id, argument] = process.argv.slice(2);
const inbox = new Inbox(process.env.DATABASE_PATH || '/data/reports.sqlite', { recoverSending: false });
try {
  if (command === 'status') {
    console.log(JSON.stringify(inbox.db.prepare('SELECT state,COUNT(*) AS count FROM reports GROUP BY state').all()));
    console.log(JSON.stringify(inbox.db.prepare("SELECT id,accepted_at FROM reports WHERE state='uncertain'").all()));
  } else {
    if (!validId(id)) throw new Error('INVALID_ID');
    const row = inbox.db.prepare("SELECT payload FROM reports WHERE id=? AND state='uncertain'").get(id);
    if (!row?.payload) throw new Error('NOT_UNCERTAIN');
    if (command === 'delivered') {
      const number = Number(argument);
      if (!Number.isSafeInteger(number) || number < 1) throw new Error('INVALID_ISSUE_NUMBER');
      const github = new GitHub(process.env.GITHUB_REPOSITORY, process.env.GITHUB_TOKEN);
      await github.verify(); await github.verifyIssue(number, JSON.parse(row.payload));
      inbox.delivered(id, number);
    } else if (command === 'retry' && argument === '--confirmed-absent') {
      // Explicit human decision after checking GitHub; never an automatic retry.
      inbox.db.prepare("UPDATE reports SET state='queued' WHERE id=? AND state='uncertain'").run(id);
    } else throw new Error('INVALID_COMMAND');
    console.log('RECONCILED');
  }
} catch { console.error('ADMIN_COMMAND_FAILED'); process.exitCode = 1; }
finally { inbox.close(); }
