// Read-only: inspect Folio link tags and possible jump destinations in the supplied database.
import { DatabaseSync } from 'node:sqlite';

const path = process.argv[2] ?? '.reference/vedabase.ivd';
const db = new DatabaseSync(path, { readOnly: true });
const print = (label, rows) => console.log(label, JSON.stringify(rows).replace(/[^\x20-\x7e]/g,
  (c) => `\\u${c.charCodeAt(0).toString(16).padStart(4, '0')}`));
try {
  print('levels', db.prepare('SELECT * FROM levels ORDER BY id').all());
  print('contents level counts', db.prepare('SELECT level, count(*) AS n FROM contents GROUP BY level ORDER BY level').all());
  print('bg 2.26', db.prepare("SELECT record, parent, level, title, simpletitle, next_sibling FROM contents WHERE simpletitle IN ('bg 2.26','sb 7.6.8','730707bg.lon','fm 1 dedication')").all());
  print('simpletitle dup sample', db.prepare("SELECT simpletitle, count(*) AS n FROM contents GROUP BY simpletitle HAVING n > 1 ORDER BY n DESC LIMIT 8").all());
  print('jumplinks dup titles', db.prepare('SELECT title, count(*) AS n, min(recid) AS first FROM jumplinks GROUP BY title HAVING n > 1 ORDER BY n DESC LIMIT 6').all());
  print('jumplinks distinct', db.prepare('SELECT count(DISTINCT title) AS n FROM jumplinks').get());
  // Distribution of query shapes: text between <QL:Query," and the closing quote.
  const rows = db.prepare("SELECT plain FROM texts WHERE plain LIKE '%<QL%'").all();
  const shapes = new Map(); const examples = new Map();
  const re = /<QL:Query,"((?:[^"]|"")*)"/gi;
  let total = 0;
  for (const { plain } of rows) for (const m of plain.matchAll(re)) {
    total++;
    const q = m[1];
    const head = q.match(/^\[?\s*([A-Za-z]+)\s+([^,\]]*)/);
    const key = head ? `${head[1]} ${head[2].trim()}` : q.slice(0, 30);
    shapes.set(key, (shapes.get(key) ?? 0) + 1);
    if (!examples.has(key)) examples.set(key, q);
  }
  console.log('QL total', total);
  print('QL shapes', [...shapes.entries()].sort((a, b) => b[1] - a[1]).slice(0, 25).map(([k, n]) => ({ k, n, ex: examples.get(k) })));
  print('PX classes', db.prepare("SELECT class, count(*) AS n FROM popup GROUP BY class").all());
  print('popup dup', db.prepare('SELECT count(*) AS n, count(DISTINCT title) AS d FROM popup').get());
  print('audio objects', db.prepare("SELECT objectType, count(*) AS n FROM objects GROUP BY objectType").all());
} finally { db.close(); }
