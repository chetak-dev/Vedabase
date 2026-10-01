import { DatabaseSync } from 'node:sqlite';
import { resolve } from 'node:path';

// Read-only streaming audit: counts and directive examples, never a corpus export.
const db = new DatabaseSync(resolve(process.argv[2] ?? '.reference/vedabase.ivd'), { readOnly: true });
const tags = new Map();
let rows = 0;
try {
  for (const row of db.prepare('SELECT recid,plain FROM texts ORDER BY recid').iterate()) {
    rows++;
    const source = row.plain ?? '';
    let offset = 0;
    while (offset < source.length) {
      const start = source.indexOf('<', offset);
      if (start < 0) break;
      let end = start + 1, quoted = false;
      for (; end < source.length; end++) {
        const c = source[end];
        if (c === '"') quoted = !quoted;
        else if (!quoted && (c === '>' || c === '<')) break;
      }
      if (end >= source.length) break;
      if (source[end] === '<') { offset = end; continue; }
      const body = source.slice(start + 1, end);
      const code = body.split(':', 1)[0].trim().toUpperCase();
      const item = tags.get(code) ?? { code, count: 0, examples: [] };
      item.count++;
      if (item.examples.length < 2 && !item.examples.some(e => e.tag === body)) {
        item.examples.push({ record: row.recid, tag: body.slice(0, 160) });
      }
      tags.set(code, item);
      offset = end + 1;
    }
  }
  console.log(JSON.stringify({ rows, tags: [...tags.values()].sort((a, b) => b.count - a.count) }, null, 2));
} finally { db.close(); }