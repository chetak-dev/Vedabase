// Read-only: root collections and the largest sections (for home ordering and size limits). Usage: node tools/survey-roots.mjs <ivd>
import { DatabaseSync } from 'node:sqlite';
const db = new DatabaseSync(process.argv[2] ?? '.reference/vedabase.ivd', { readOnly: true });
const esc = (s) => JSON.stringify(s).replace(/[^\x20-\x7e]/g, (c) => `\\u${c.charCodeAt(0).toString(16).padStart(4, '0')}`);
console.log('--- roots');
for (const r of db.prepare('SELECT record,next_sibling,title,node_code,node_children,level FROM contents WHERE parent=0 ORDER BY record').all()) {
  console.log(r.record, r.next_sibling, r.node_code, r.node_children, r.level, esc(r.title));
}
console.log('--- glossary-like nodes');
for (const r of db.prepare("SELECT c.record,c.next_sibling,c.parent,c.level,c.node_children,c.title,(SELECT title FROM contents p WHERE p.record=c.parent) parent_title FROM contents c WHERE c.title LIKE '%Glossar%' ORDER BY c.record LIMIT 20").all()) {
  const rows = db.prepare('SELECT count(*) n, sum(length(plain)) chars FROM texts WHERE recid BETWEEN ? AND ?').get(r.record, r.next_sibling);
  console.log(r.record, r.next_sibling, 'parent', r.parent, 'level', r.level, 'children', r.node_children, 'rows', rows.n, 'chars', rows.chars, esc(r.title), 'in', esc(r.parent_title ?? ''));
}
db.close();
