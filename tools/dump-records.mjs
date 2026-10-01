// Read-only: dump paragraph styles/text for a record range of the supplied database.
import { DatabaseSync } from 'node:sqlite';

const [path = '.reference/vedabase.ivd', from = '1641', to = '1669'] = process.argv.slice(2);
const db = new DatabaseSync(path, { readOnly: true });
try {
  if (from.startsWith('title=')) {
    console.log(JSON.stringify(db.prepare('SELECT record,next_sibling,title,level FROM contents WHERE simpletitle=?').all(from.slice(6))));
    process.exit(0);
  }
  const rows = db.prepare('SELECT recid, stylename, levelname, substr(plain,1,220) AS plain FROM texts WHERE recid BETWEEN ? AND ? ORDER BY recid')
    .all(Number(from), Number(to));
  for (const r of rows) console.log(r.recid, JSON.stringify(r.stylename?.trim()), JSON.stringify(r.levelname?.trim()), JSON.stringify(r.plain).replace(/[^\x20-\x7e]/g, c => `\\u${c.charCodeAt(0).toString(16).padStart(4, '0')}`));
} finally { db.close(); }
