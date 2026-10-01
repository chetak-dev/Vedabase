// Read-only survey of combined verse titles per book. Usage: node tools/survey-verse-titles.mjs <ivd>
import { DatabaseSync } from 'node:sqlite';
const db = new DatabaseSync(process.argv[2] ?? '.reference/vedabase.ivd', { readOnly: true });
const rows = db.prepare("SELECT title FROM contents WHERE level=7").all();
const shapes = new Map();
for (const { title } of rows) {
  const key = title.replace(/[0-9]+/g, '#');
  shapes.set(key, (shapes.get(key) ?? 0) + 1);
}
console.log('verse rows', rows.length, 'shapes', shapes.size);
for (const [k, v] of [...shapes.entries()].sort((a, b) => b[1] - a[1]).slice(0, 60)) console.log(v, JSON.stringify(k));
console.log('--- combined, non-Bg samples');
for (const r of db.prepare("SELECT title FROM contents WHERE level=7 AND (title LIKE '%,%' OR title LIKE '%-%') AND title NOT LIKE 'Bg%' LIMIT 25").all()) console.log(JSON.stringify(r.title));
db.close();
