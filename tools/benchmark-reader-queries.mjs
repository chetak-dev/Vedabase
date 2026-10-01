// Local read-only query plans/timings. Never dumps scripture or changes the source database.
import { DatabaseSync } from 'node:sqlite';
import { performance } from 'node:perf_hooks';
const db = new DatabaseSync(process.argv[2], { readOnly: true });
function measure(label, sql, args) {
  const plan = db.prepare(`EXPLAIN QUERY PLAN ${sql}`).all(...args).map(row => row.detail);
  const start = performance.now();
  const rows = db.prepare(sql).all(...args);
  console.log(JSON.stringify({ label, ms: +(performance.now() - start).toFixed(2), rows: rows.length, plan }));
}
try {
  console.log(JSON.stringify(db.prepare("SELECT name,sql FROM sqlite_master WHERE type='index' AND tbl_name IN ('contents','texts')").all()));
  const styles = ['PA_Translation','PA_Spec_Translation','PA_Deity_95Book_95Translation','PA_Prema_45Dhvani_Translation',
    'PA_TKQ_47TLK_Translation','PA_Sloka_Translation','PA_Purple_95BG_95Translations','PA_TKQTranslation'];
  const selection = "FROM contents v JOIN texts h ON h.recid=v.record JOIN texts p ON p.recid BETWEEN v.record AND v.next_sibling " +
    "WHERE v.parent=? AND trim(h.levelname,char(9)||char(10)||char(13)||' ')='LE_Verse_Number' " +
    `AND trim(p.stylename,char(9)||char(10)||char(13)||' ') IN (${styles.map(() => '?').join(',')})`;
  for (const chapter of [1635, 2272, 273506]) {
    measure(`chapter ${chapter} existing count`, `SELECT count(*),sum(length(p.plain)) ${selection}`, [chapter, ...styles]);
    measure(`chapter ${chapter} existing translations`, `SELECT v.record,p.plain ${selection} ORDER BY v.record,p.recid`, [chapter, ...styles]);
    const node = db.prepare('SELECT record,next_sibling FROM contents WHERE record=?').get(chapter);
    measure(`chapter ${chapter} bounded translation scan`, `SELECT recid,plain FROM texts WHERE recid BETWEEN ? AND ? AND trim(stylename,char(9)||char(10)||char(13)||' ') IN (${styles.map(() => '?').join(',')}) ORDER BY recid`, [node.record, node.next_sibling, ...styles]);
  }
  for (const [record, end] of [[1641, 13063], [113900, 203111]]) {
    measure(`next verse ${record}`, "SELECT c.record,c.parent FROM contents c LEFT JOIN texts t ON t.recid=c.record WHERE c.record>? AND c.record BETWEEN ? AND ? AND coalesce(t.levelname,'')=? ORDER BY c.record ASC LIMIT 1", [record, 1428, end, 'LE_Verse_Number']);
  }
} finally { db.close(); }