// Read-only metadata/timing evidence for the user's CC Madhya 1.1 report; no passage bodies.
import { DatabaseSync } from 'node:sqlite';
const db = new DatabaseSync(process.argv[2], { readOnly: true });
const print = (label, data) => console.log(label, JSON.stringify(data).replace(/[^\x20-\x7e]/g,
  c => `\\u${c.charCodeAt(0).toString(16).padStart(4, '0')}`));
try {
  const node = db.prepare('SELECT c.record,c.parent,c.title,c.next_sibling,t.levelname FROM contents c LEFT JOIN texts t ON t.recid=c.record WHERE c.record=?');
  const targets = db.prepare("SELECT record FROM contents WHERE simpletitle IN ('cc madhya 1.1','madhya 1.1')").all();
  print('Reference candidates', db.prepare("SELECT record,title,simpletitle FROM contents WHERE simpletitle>='madhya 1' AND simpletitle<'madhya 2' ORDER BY simpletitle LIMIT 6").all());
  for (const target of targets) {
    let heading = node.get(target.record);
    while (heading) {
      print('Ancestry', heading);
      print('Sizes', db.prepare('SELECT count(*) rows,sum(length(plain)) chars,max(length(cast(plain as blob))) maxParagraphBytes FROM texts WHERE recid BETWEEN ? AND ?').get(heading.record, heading.next_sibling));
      print('Headings', db.prepare('SELECT count(*) n FROM contents WHERE record BETWEEN ? AND ?').get(heading.record, heading.next_sibling));
      print('Direct types', db.prepare('SELECT t.levelname,count(*) n FROM contents c LEFT JOIN texts t ON t.recid=c.record WHERE c.parent=? GROUP BY t.levelname').all(heading.record));
      heading = heading.parent ? node.get(heading.parent) : null;
    }
  }
  print('Verse styles', db.prepare("SELECT stylename,levelname,count(*) n FROM texts WHERE recid BETWEEN 273500 AND 274100 GROUP BY stylename,levelname").all());
  for (const prefix of ['k','kr','devot']) {
    const end = prefix.slice(0,-1) + String.fromCharCode(prefix.charCodeAt(prefix.length-1)+1);
    for (const projection of ['DISTINCT substr(word,1,513)', 'word']) {
      const sql = `SELECT ${projection} FROM words WHERE word>=? AND word<? AND idx IN ('','Devanagari') ${projection==='word'?'GROUP BY word':''} ORDER BY word LIMIT 96`;
      const start = performance.now(); const rows = db.prepare(sql).all(prefix,end);
      print('Suggestions', {prefix,projection,rows:rows.length,ms:performance.now()-start,plan:db.prepare('EXPLAIN QUERY PLAN '+sql).all(prefix,end)});
    }
  }
} finally { db.close(); }