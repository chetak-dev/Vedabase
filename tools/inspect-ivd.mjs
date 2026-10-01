// Read-only diagnostics for a user-supplied Vedabase database. Requires Node 22+.
import { DatabaseSync } from 'node:sqlite';
import { statSync } from 'node:fs';

const path = process.argv[2];
if (!path) {
  console.error('Usage: node tools/inspect-ivd.mjs <database.ivd> [table]');
  process.exit(1);
}

const quote = (value) => `"${value.replaceAll('"', '""')}"`;
const preview = (value) => {
  if (value instanceof Uint8Array) {
    return { bytes: value.length, header: Buffer.from(value).subarray(0, 32).toString('hex') };
  }
  if (typeof value === 'string' && value.length > 700) {
    return `${value.slice(0, 700)}… (${value.length} characters)`;
  }
  return value;
};

console.log(`File: ${path}\nSize: ${statSync(path).size} bytes`);
const db = new DatabaseSync(path, { readOnly: true });
try {
  if (process.argv[3] === '--chapter-overview') {
    const print = (label, rows) => console.log(label, JSON.stringify(rows).replace(/[^\x20-\x7e]/g,
      (character) => `\\u${character.charCodeAt(0).toString(16).padStart(4, '0')}`));
    print('Translation styles', db.prepare("SELECT stylename,count(*) AS count FROM texts WHERE stylename LIKE '%Translat%' GROUP BY stylename ORDER BY count DESC").all());
    print('Translation section metadata', db.prepare("SELECT c.record,c.parent,c.title,c.next_sibling,t.stylename,t.levelname FROM contents c JOIN texts t ON t.recid=c.record WHERE c.parent IN (1641,1660,1732,1865) ORDER BY c.record").all());
    const verses = db.prepare("SELECT c.record,c.next_sibling FROM contents c JOIN texts t ON t.recid=c.record WHERE c.parent=? AND t.levelname='LE_Verse_Number' ORDER BY c.record");
    for (const chapter of [1635, 2272, 13157, 273506]) {
      const entries = verses.all(chapter);
      let missing = 0, paragraphs = 0, characters = 0;
      for (const verse of entries) {
        const stats = db.prepare("SELECT count(*) AS count,coalesce(sum(length(plain)),0) AS characters FROM texts WHERE recid BETWEEN ? AND ? AND trim(stylename,char(13)||char(10)||' ')='PA_Translation'").get(verse.record, verse.next_sibling);
        if (!stats.count) missing++;
        paragraphs += stats.count; characters += stats.characters;
      }
      print('Chapter translation stats', { chapter, verses: entries.length, missing, paragraphs, characters });
    }
  } else if (process.argv[3] === '--pagination') {
    const print = (label, rows) => console.log(label, JSON.stringify(rows).replace(/[^\x20-\x7e]/g,
      (character) => `\\u${character.charCodeAt(0).toString(16).padStart(4, '0')}`));
    print('Content levels', db.prepare('SELECT * FROM levels ORDER BY id').all());
    const nodes = [1428, 1465, 1497, 1635, 1641, 1643, 1653, 1655, 1732, 1865, 2272, 13064, 13065, 203112, 203113, 354416, 727003];
    for (const record of nodes) {
      print('Node', db.prepare('SELECT c.record,c.parent,c.title,c.level,c.next_sibling,c.node_children,t.levelname,t.stylename FROM contents c LEFT JOIN texts t ON t.recid=c.record WHERE c.record=?').get(record));
    }
    print('Gita chapter boundary', db.prepare('SELECT c.record,c.parent,c.title,c.next_sibling,t.levelname FROM contents c LEFT JOIN texts t ON t.recid=c.record WHERE c.record BETWEEN 2200 AND 2290 ORDER BY c.record').all());
    print('Largest complete verse', db.prepare("SELECT max(next_sibling-record+1) AS records FROM contents WHERE level=(SELECT id FROM levels WHERE level='LE_Verse_Number')").get());
    print('Largest chapter spans', db.prepare("SELECT title,record,parent,next_sibling-record+1 AS records FROM contents WHERE level=(SELECT id FROM levels WHERE level='LE_Chapter') ORDER BY records DESC LIMIT 5").all());
  } else if (process.argv[3] === '--reader') {
    const print = (label, rows) => console.log(label, JSON.stringify(rows).replace(/[^\x20-\x7e]/g,
      (character) => `\\u${character.charCodeAt(0).toString(16).padStart(4, '0')}`));
    print('Record identity', db.prepare('SELECT min(recid) AS first,max(recid) AS last,count(*) AS rows,count(DISTINCT recid) AS ids FROM texts').get());
    print('Longest paragraph', db.prepare('SELECT max(length(CAST(plain AS BLOB))) AS bytes FROM texts').get());
    for (const record of [1465, 1497, 1635, 1641, 1655]) {
      const node = db.prepare('SELECT record,title,next_sibling FROM contents WHERE record=?').get(record);
      print('Section', node);
      print('Paragraph shape', db.prepare('SELECT recid,stylename,levelname,length(plain) AS characters FROM texts WHERE recid BETWEEN ? AND ? ORDER BY recid LIMIT 30').all(record, node.next_sibling));
    }
    const tags = new Map();
    for (const row of db.prepare('SELECT plain FROM texts WHERE recid BETWEEN 1465 AND 1660').all()) {
      for (const match of row.plain.matchAll(/<\/?([A-Z]+)[^>]*>/g)) tags.set(match[1], (tags.get(match[1]) ?? 0) + 1);
    }
    print('Opening section tag types', [...tags.entries()].sort((a, b) => b[1] - a[1]));
  } else if (process.argv[3] === '--browse') {
    const print = (label, rows) => {
      console.log(`\n${label}`);
      for (const row of rows) console.log(JSON.stringify(row).replace(/[^\x20-\x7e]/g,
        (character) => `\\u${character.charCodeAt(0).toString(16).padStart(4, '0')}`));
    };
    print('Largest direct child lists', db.prepare('SELECT parent,count(*) AS count FROM contents GROUP BY parent ORDER BY count DESC LIMIT 5').all());
    for (const parent of [1428, 1635, 1641, 13064, 203112]) {
      print(`Children of ${parent}`, db.prepare('SELECT record,parent,title,node_children,node_code,level FROM contents WHERE parent=? ORDER BY record LIMIT 30').all(parent));
    }
  } else if (process.argv[3] === '--overview') {
    const print = (label, sql, ...args) => {
      console.log(`\n${label}`);
      for (const row of db.prepare(sql).all(...args)) {
        // ASCII output avoids Windows console code-page corruption in diagnostics.
        const text = JSON.stringify(Object.fromEntries(Object.entries(row).map(([key, value]) => [key, preview(value)])));
        console.log(text.replace(/[^\x20-\x7e]/g, (character) => `\\u${character.charCodeAt(0).toString(16).padStart(4, '0')}`));
      }
    };
    print('Indexes', "SELECT name, sql FROM sqlite_master WHERE type = 'index'");
    print('Document metadata', 'SELECT * FROM docinfo');
    print('Root sections', 'SELECT * FROM contents WHERE parent = 0 ORDER BY record');
    print('Gita children', 'SELECT * FROM contents WHERE parent = 1428 ORDER BY record LIMIT 25');
    print('Gita paragraphs', 'SELECT * FROM texts WHERE recid BETWEEN 1620 AND 1650');
    print('Known search terms', "SELECT word, uid, indexbase, idx, length(data) AS bytes, hex(substr(data,1,30)) AS head FROM words WHERE word IN ('krishna', 'krsna', 'devotion', 'arjuna') LIMIT 12");
    print('Index categories', 'SELECT DISTINCT idx FROM words');
    print('Script styles', "SELECT * FROM styles_detail WHERE name = 'font-family' LIMIT 30");
    print('Objects by type', 'SELECT objectType, count(*) AS count FROM objects GROUP BY objectType');
    print('Audio markup', "SELECT recid, plain FROM texts WHERE plain LIKE '%Bg-01-01%' LIMIT 2");
  } else {
  const tables = db.prepare("SELECT name, sql FROM sqlite_master WHERE type = 'table' ORDER BY name").all();
  for (const table of tables.filter((entry) => !process.argv[3] || entry.name === process.argv[3])) {
    console.log(`\n${table.name}: ${table.sql}`);
    console.log('Rows:', db.prepare(`SELECT count(*) AS n FROM ${quote(table.name)}`).get().n);
    for (const row of db.prepare(`SELECT * FROM ${quote(table.name)} LIMIT 3`).all()) {
      console.log(JSON.stringify(Object.fromEntries(Object.entries(row).map(([key, value]) => [key, preview(value)]))));
    }
  }
  }
} finally {
  db.close();
}