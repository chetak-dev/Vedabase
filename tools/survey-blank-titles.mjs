// Read-only: list blank-titled verse/chapter nodes and their neighbours. Usage: node tools/survey-blank-titles.mjs <ivd>
import { DatabaseSync } from 'node:sqlite';
const db = new DatabaseSync(process.argv[2] ?? '.reference/vedabase.ivd', { readOnly: true });
const total = db.prepare("SELECT level, count(*) n FROM contents WHERE trim(title)='' GROUP BY level").all();
console.log('blank titles by level', JSON.stringify(total));
const rows = db.prepare(`SELECT c.record, c.level, c.parent, c.next_sibling,
    (SELECT title FROM contents p WHERE p.parent=c.parent AND p.record<c.record ORDER BY p.record DESC LIMIT 1) prev,
    (SELECT title FROM contents n WHERE n.parent=c.parent AND n.record>c.record ORDER BY n.record LIMIT 1) next,
    (SELECT substr(plain,1,80) FROM texts t WHERE t.recid>c.record AND t.recid<=c.next_sibling AND t.stylename LIKE 'PA_Textnum%' LIMIT 1) textnum
  FROM contents c WHERE trim(c.title)='' AND c.level BETWEEN 6 AND 7 LIMIT 40`).all();
for (const r of rows) console.log(JSON.stringify(r).replace(/[^\x20-\x7e]/g, c => `\\u${c.charCodeAt(0).toString(16).padStart(4, '0')}`));
db.close();
