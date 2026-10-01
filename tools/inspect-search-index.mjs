// Read-only search diagnostics. Prints metadata/counts/plans, never passage bodies.
import { DatabaseSync } from 'node:sqlite';
const db = new DatabaseSync(process.argv[2], { readOnly: true });
const print = (label, data) => console.log(label, JSON.stringify(data).replace(/[^\x20-\x7e]/g,
  c => `\\u${c.charCodeAt(0).toString(16).padStart(4, '0')}`));
try {
  print('Index schema', db.prepare("SELECT name,sql FROM sqlite_master WHERE type='index' AND tbl_name IN ('words','contents','texts')").all());
  print('Word categories', db.prepare('SELECT idx,count(*) n,sum(length(data)) bytes FROM words GROUP BY idx').all());
  print('Books/collections', db.prepare("SELECT c.record,c.parent,c.title,c.next_sibling,t.levelname FROM contents c JOIN texts t ON t.recid=c.record WHERE c.parent=0 ORDER BY c.record").all());
  print('Independent work counts', db.prepare("SELECT levelname,count(*) n FROM texts WHERE levelname IN ('LE_Book','LE_Collection') GROUP BY levelname").all());
  for (const query of ['krishna','krsna','arjuna','consciousness','the','भगवान']) {
    print(`Term ${query}`, db.prepare('SELECT rowid,word,indexbase,idx,length(data) bytes,hex(substr(data,1,18)) head FROM words WHERE word=?').all(query));
  }
  print('Completion plan', db.prepare("EXPLAIN QUERY PLAN SELECT word FROM words WHERE word>=? AND word<? AND idx IN ('','Devanagari') GROUP BY word ORDER BY word LIMIT 30").all('krs','krt'));
  print('Reference plan', db.prepare('EXPLAIN QUERY PLAN SELECT record,title FROM contents WHERE simpletitle>=? AND simpletitle<? ORDER BY simpletitle LIMIT 12').all('bg 2','bg 3'));
  print('References', db.prepare("SELECT title,simpletitle,record FROM contents WHERE record IN (1641,2272,1428,1865,13064)").all());
} finally { db.close(); }