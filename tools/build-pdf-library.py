"""Append Word-generated PDF guides to a COPY of a Vedabase .ivd library, one book at a time.

The original library (--source) is only read. The first run copies it to --output and creates the
collection; later runs append only PDFs that are not in --output yet (matched by file content), so
books already added keep their record numbers and bookmarks. Work happens on a .part copy that is
renamed into place after SQLite's quick_check, so a failed run never leaves a half-built file behind.

  python tools/build-pdf-library.py --pdf "D:/.../Dham Yatra Guides" --collection "Dham Yatra Guides"
  python tools/build-pdf-library.py --pdf "D:/.../Dham Yatra Guides" --collection "Dham Yatra Guides" --dry-run
"""

from __future__ import annotations

import argparse
import contextlib
import hashlib
import re
import shutil
import sqlite3
import statistics
import struct
import sys
import time
import unicodedata
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

import fitz  # PyMuPDF

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_SOURCE = ROOT / ".reference" / "vedabase.ivd"
DEFAULT_OUTPUT = ROOT / ".reference" / "vedabase-with-guides.ivd"
BUCKET = 65536
# Level ids from the source `levels` table; checked at build time.
LEVELS = {"LE_Collection": 1, "LE_Book": 3, "LE_Chapter": 6}


# ---------------------------------------------------------------- PDF parsing


@dataclass
class Span:
    text: str
    bold: bool
    italic: bool
    size: float


@dataclass
class Line:
    page: int
    y0: float
    y1: float
    x0: float
    x1: float
    spans: list[Span]

    @property
    def text(self) -> str:
        return "".join(s.text for s in self.spans)

    @property
    def blank(self) -> bool:
        return not self.text.strip()

    @property
    def size(self) -> float:
        weights: dict[float, int] = defaultdict(int)
        for s in self.spans:
            weights[round(s.size, 1)] += len(s.text.strip())
        return max(weights, key=weights.get)

    @property
    def all_bold(self) -> bool:
        return all(s.bold for s in self.spans if s.text.strip())


@dataclass
class Image:
    page: int
    y0: float


@dataclass
class Block:
    kind: str  # heading | para | image
    lines: list[Line]
    centered: bool = False
    caption: bool = False
    preamble: bool = False  # part of the book title on page 1


@dataclass
class Chapter:
    title: str
    paragraphs: list[str] = field(default_factory=list)


@dataclass
class Book:
    source: Path
    title: str
    chapters: list[Chapter]


def read_elements(doc: fitz.Document) -> list[Line | Image]:
    elements: list[Line | Image] = []
    for number, page in enumerate(doc):
        items: list[Line | Image] = [Image(number, info["bbox"][1]) for info in page.get_image_info()]
        height = page.rect.height
        for block in page.get_text("dict")["blocks"]:
            if block["type"] != 0:
                continue
            for raw in block["lines"]:
                spans = [Span(s["text"], "bold" in s["font"].lower(), "italic" in s["font"].lower(), s["size"])
                         for s in raw["spans"] if s["text"]]
                if not spans:
                    continue
                x0, y0, x1, y1 = raw["bbox"]
                line = Line(number, y0, y1, x0, x1, spans)
                if not line.blank and y0 > height - 40 and re.fullmatch(r"\s*\d{1,3}\s*", line.text):
                    continue  # page number
                items.append(line)
        items.sort(key=lambda item: (item.y0, 0 if isinstance(item, Image) else 1))
        elements.extend(items)
    return elements


def hyphen_wrapped(line: Line) -> bool:
    tail = line.text.rstrip()
    return len(tail) > 1 and tail.endswith("-") and not tail[-2].isspace()


def blocks_from(elements: list[Line | Image]) -> list[Block]:
    text_lines = [e for e in elements if isinstance(e, Line) and not e.blank]
    if sum(len(line.text.strip()) for line in text_lines) < 200:
        raise ValueError("No text layer found. Scanned PDFs need OCR first.")
    weights: dict[float, int] = defaultdict(int)
    for line in text_lines:
        weights[round(line.size * 2) / 2] += len(line.text.strip())
    body_size = max(weights, key=weights.get)
    body = [line for line in text_lines if abs(line.size - body_size) <= 1]
    left = statistics.mode(round(line.x0) for line in body)
    left_lines = [line.x1 for line in body if abs(line.x0 - left) < 2]
    right = statistics.quantiles(left_lines, n=20)[-1] if len(left_lines) > 1 else max(left_lines)

    def indented(line: Line) -> bool:
        return line.x0 > left + 2.5

    def short(line: Line) -> bool:
        return line.x1 < right - 100

    def is_heading(line: Line) -> bool:
        return line.all_bold and line.size >= body_size + 1.5

    blocks: list[Block] = []
    current: Block | None = None
    previous: Line | None = None
    after_image: int | None = None

    def flush() -> None:
        nonlocal current, after_image
        if current is not None:
            first = current.lines[0]
            # Centred lines can start at the margin too, so decide for the whole paragraph.
            current.centered = any(indented(line) for line in current.lines)
            current.caption = (after_image == first.page and current.centered
                               and all(line.all_bold for line in current.lines))
            after_image = None
            blocks.append(current)
        current = None

    for element in elements:
        if isinstance(element, Image):
            flush()
            blocks.append(Block("image", []))
            after_image = element.page
            previous = None
            continue
        line = element
        if line.blank:
            flush()
            continue
        if after_image is not None and after_image != line.page:
            after_image = None
        if is_heading(line):
            flush()
            last = blocks[-1] if blocks else None
            wrapped = (last is not None and last.kind == "heading" and previous is last.lines[-1]
                       and abs(previous.size - line.size) < 0.5 and line.y0 - previous.y0 < line.size * 1.75)
            if wrapped:
                last.lines.append(line)
            else:
                blocks.append(Block("heading", [line], centered=indented(line)))
            previous = line
            continue
        if current is not None and previous is not None:
            title_only = all(l.all_bold for l in current.lines) and indented(current.lines[0])
            breaks = (
                (previous.page == line.page and line.y0 - previous.y0 > line.size * 1.75)
                or (short(previous) and not hyphen_wrapped(previous))
                or (title_only and not line.all_bold)
            )
            if breaks:
                flush()
        if current is None:
            current = Block("para", [])
        current.lines.append(line)
        previous = line
    flush()
    return blocks


def render(lines: list[Line], plain: bool = False) -> str:
    runs: list[tuple[str, bool | None, bool | None]] = []
    for index, line in enumerate(lines):
        spans = list(line.spans)
        while spans and not spans[-1].text.strip():
            spans.pop()
        if spans:
            spans[-1] = Span(spans[-1].text.rstrip(), spans[-1].bold, spans[-1].italic, spans[-1].size)
        for span in spans:
            if span.text.strip():
                runs.append((span.text, span.bold and not plain, span.italic and not plain))
            else:
                runs.append((" ", None, None))
        if index < len(lines) - 1:
            # Word wraps after a hyphen ("Srimad-" / "Bhagavatam", URLs); keep the hyphen, drop the break.
            if not hyphen_wrapped(line):
                runs.append((" ", None, None))

    out: list[str] = []
    bold = italic = False
    pending = ""
    for text, want_bold, want_italic in runs:
        if "<" in text or ">" in text:
            raise ValueError(f"Angle bracket in source text needs an escape rule: {text!r}")
        if want_bold is None:
            pending += text
            continue
        core = text.strip()
        # Close styles before the space and open them after it: "<IT+>Bhagavatam<IT-> states".
        if bold and not want_bold:
            out.append("<BD->")
            bold = False
        if italic and not want_italic:
            out.append("<IT->")
            italic = False
        out.append(pending + text[: len(text) - len(text.lstrip())])
        if want_bold and not bold:
            out.append("<BD+>")
            bold = True
        if want_italic and not italic:
            out.append("<IT+>")
            italic = True
        out.append(core)
        pending = text[len(text.rstrip()):]
    if italic:
        out.append("<IT->")
    if bold:
        out.append("<BD->")
    return re.sub(r"\s+", " ", "".join(out)).strip()


def plain_text(lines: list[Line]) -> str:
    return re.sub(r"\s+", " ", " ".join(line.text for line in lines)).strip()


SMALL_WORDS = {"a", "an", "and", "as", "at", "by", "for", "from", "in", "of", "on", "or", "the", "to", "with"}
KEEP_CAPS = {"ISKCON", "BBT", "HH", "TV", "AD", "BC", "USA", "UK", "II", "III", "IV"}


def tidy_title(text: str) -> str:
    """Trim trailing ':-' and turn ALL-CAPS headings into title case for the contents list."""
    text = re.sub(r"\s+", " ", text).strip().rstrip(" :-–—").strip()
    letters = [c for c in text if c.isalpha()]
    if len(letters) < 4 or sum(c.isupper() for c in letters) < 0.8 * len(letters):
        return text

    def fix(match: re.Match) -> str:
        word = match.group(0)
        if word in KEEP_CAPS:
            return word
        low = word.lower()
        return low if match.start() > 0 and low in SMALL_WORDS else low[:1].upper() + low[1:]

    return re.sub(r"[A-Za-z]+(?:['’][A-Za-z]+)*", fix, text)


def split_heading(lines: list[Line]) -> tuple[list[Line], list[Line]]:
    """Title = lines up to the last one in the largest size (keeps 'X / and / Y'); the rest is a subtitle."""
    largest = max(line.size for line in lines)
    last = max(i for i, line in enumerate(lines) if line.size >= largest - 0.5)
    return lines[: last + 1], lines[last + 1:]


def standalone_heading(block: Block) -> bool:
    text = plain_text(block.lines)
    return (block.kind == "para" and not block.caption and len(block.lines) <= 2
            and all(line.all_bold for line in block.lines) and 3 <= len(text) <= 90
            and text[0].isupper() and text[-1] not in ".,;" and "://" not in text)


def parse_pdf(path: Path) -> Book:
    with fitz.open(path) as doc:
        if doc.is_encrypted:
            raise ValueError(f"{path.name} is encrypted")
        blocks = blocks_from(read_elements(doc))

    # The title is the run of headings around the largest heading on page 1, even after a photo or caption.
    first_page = [i for i, b in enumerate(blocks) if b.kind == "heading" and b.lines[0].page == 0]
    if first_page:
        top = max(line.size for i in first_page for line in blocks[i].lines)
        start = end = next(i for i in first_page if any(line.size >= top - 0.5 for line in blocks[i].lines))
        while start > 0 and blocks[start - 1].kind == "heading" and blocks[start - 1].lines[0].page == 0:
            start -= 1
        while end + 1 < len(blocks) and blocks[end + 1].kind == "heading" and blocks[end + 1].lines[0].page == 0:
            end += 1
        for block in blocks[start:end + 1]:
            block.preamble = True

    # PDFs without larger headings mark sections with standalone bold lines instead (unless all text is bold).
    text_chars = [(len(line.text.strip()), line.all_bold) for b in blocks if b.kind == "para" for line in b.lines]
    mostly_bold = sum(n for n, bold in text_chars if bold) > 0.5 * sum(n for n, _ in text_chars)
    if not mostly_bold and sum(1 for b in blocks if b.kind == "heading" and not b.preamble) < 3:
        for block in blocks:
            if standalone_heading(block):
                block.kind = "heading"

    preamble = [line for b in blocks if b.kind == "heading" and b.preamble for line in b.lines]
    title, subtitle = split_heading(preamble) if preamble else ([], [])
    book_title = tidy_title(plain_text(title)) if title else path.stem
    chapters: list[Chapter] = []
    current = Chapter("Introduction")
    if subtitle:
        current.paragraphs.append("<JU:CN><IT+>" + render(subtitle, plain=True) + "<IT->")
    run: list[Line] = []

    def start_chapter() -> None:
        nonlocal current, run
        if not run:
            return
        heading, sub = split_heading(run)
        title = tidy_title(plain_text(heading))
        if len(title) > 150:
            # Too long for a contents entry: a bold statement, not a section title.
            current.paragraphs.append("<JU:CN>" + render(run))
            run = []
            return
        # A heading followed directly by another heading (a part title) has nothing of its own to show.
        if current.paragraphs:
            chapters.append(current)
        current = Chapter(title)
        if sub:
            current.paragraphs.append("<JU:CN><IT+>" + render(sub, plain=True) + "<IT->")
        run = []

    for block in blocks:
        if block.kind == "heading":
            if not block.preamble:
                run.extend(block.lines)
            continue
        if block.kind == "image":
            start_chapter()
            continue
        start_chapter()
        if block.caption:
            continue  # photo captions are meaningless without the photos
        paragraph = ("<JU:CN>" if block.centered else "") + render(block.lines)
        if current.paragraphs and current.paragraphs[-1] == paragraph:
            continue  # repeated text, e.g. the same line under consecutive photos
        current.paragraphs.append(paragraph)
    start_chapter()
    if current.paragraphs:
        chapters.append(current)
    if not chapters:
        raise ValueError(f"No readable text in {path.name}")
    return Book(path, book_title, chapters)


# ----------------------------------------------------------- index helpers


def fold(value: str) -> str:
    """Same folding as the app's WordIndex.normalize: drop marks on Latin letters only."""
    out = []
    latin = False
    for ch in unicodedata.normalize("NFD", value.lower()):
        mark = unicodedata.category(ch) in ("Mn", "Mc", "Me")
        if not mark:
            latin = "LATIN" in unicodedata.name(ch, "")
        if not mark or not latin:
            out.append(ch)
    return unicodedata.normalize("NFC", "".join(out))


def tokens(markup: str) -> list[str]:
    """Runs of letters, digits and marks, like the app's [\\p{L}\\p{N}\\p{M}]+ query tokens."""
    text = re.sub(r"<[^>]*>", " ", markup)
    words: list[str] = []
    current: list[str] = []
    for ch in text + " ":
        if unicodedata.category(ch)[0] in "LNM":
            current.append(ch)
        elif current:
            words.append(fold("".join(current)))
            current = []
    return [word for word in words if any(c.isalnum() for c in word)]


def simple_title(title: str) -> str:
    value = fold(title).replace("-", " ").replace("–", " ").replace("—", " ")
    value = re.sub(r"[^\w\s'.]", "", value)
    value = re.sub(r"(?<!\d)\.|\.(?!\d)", "", value)
    return re.sub(r"\s+", " ", value).strip()


def initials(title: str) -> str:
    return "".join(word[0] for word in re.findall(r"[a-z0-9]+", fold(title)))[:8] or "pdf"


# ------------------------------------------------------------------ building


MANIFEST = "-e PdfSource"  # docinfo rows: valuex = "<sha256>|<file name>", idx = book record


def file_hash(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def find_collection(db: sqlite3.Connection, collection: str) -> tuple[int, int] | None:
    """(record, end) of the collection, which must be the last root node so books can be appended."""
    row = db.execute("SELECT record, next_sibling FROM contents WHERE parent=0 AND (title=? OR title LIKE ?) "
                     "ORDER BY record DESC LIMIT 1", (collection, f"%. {collection}")).fetchone()
    if row is None:
        return None
    if row[1] != db.execute("SELECT max(recid) FROM texts").fetchone()[0]:
        raise RuntimeError(f"'{collection}' is not at the end of the library, so books cannot be appended to it.")
    return row


def known_sources(db: sqlite3.Connection, collection: str) -> tuple[set[str], set[str]]:
    """Hashes recorded in the manifest, and folded titles of books already in the collection."""
    hashes = {value.split("|", 1)[0] for (value,) in
              db.execute("SELECT valuex FROM docinfo WHERE name=?", (MANIFEST,))}
    found = find_collection(db, collection)
    titles = set()
    if found:
        titles = {fold(title) for (title,) in db.execute("SELECT title FROM contents WHERE parent=?", (found[0],))}
    return hashes, titles


def create_collection(db: sqlite3.Connection, collection: str) -> tuple[int, int]:
    q = db.execute
    levels = dict(q("SELECT level, id FROM levels"))
    for name, expected in LEVELS.items():
        if levels.get(name) != expected:
            raise RuntimeError(f"Unexpected levels table: {name} is {levels.get(name)}, expected {expected}")
    numbers = [int(m.group(1)) for (title,) in q("SELECT title FROM contents WHERE parent=0")
               if (m := re.match(r"\s*(\d+)\.", title))]
    title = f"{max(numbers) + 1}. {collection}" if numbers else collection
    last = q("SELECT max(recid) FROM texts").fetchone()[0]
    record = last + 1
    # The source's final node ("The End") claims every record up to 2,000,000; end it where its text ends.
    q("UPDATE contents SET next_sibling=? WHERE next_sibling>?", (last, last))
    q("INSERT INTO texts(plain, recid, showid, levelname, stylename) VALUES (?,?,NULL,?,?)",
      (" " + title, record, "LE_Collection", "LE_Collection"))
    q("INSERT INTO contents(title, record, parent, level, simpletitle, subtext, node_type, node_code, node_children, "
      "next_sibling) VALUES (?,?,0,?,?,'',0,'',0,?)", (title, record, LEVELS["LE_Collection"], simple_title(title), record))
    index_words(db, [(record, " " + title)])
    original = q("SELECT valuex FROM docinfo WHERE name='TT'").fetchone()
    q("UPDATE docinfo SET valuex=? WHERE name='TT'", (f"{original[0] if original else 'Vedabase'} + {collection}",))
    db.commit()
    print(f"Created collection '{title}' at record {record}.")
    return record, record


def index_words(db: sqlite3.Connection, texts: list[tuple[int, str]]) -> int:
    postings: dict[str, list[tuple[int, int]]] = defaultdict(list)
    for recid, plain in texts:
        for position, word in enumerate(tokens(plain)):
            if position > 0xFFFF:
                break
            postings[word].append((recid, position))
    next_uid = db.execute("SELECT max(uid) FROM words").fetchone()[0] + 1
    rows = []
    for word, hits in sorted(postings.items()):
        found = db.execute("SELECT uid FROM words WHERE word=? LIMIT 1", (word,)).fetchone()
        if found:
            uid = found[0]
        else:
            uid, next_uid = next_uid, next_uid + 1
        buckets: dict[int, bytearray] = defaultdict(bytearray)
        for recid, position in sorted(hits):
            base = recid // BUCKET * BUCKET
            buckets[base] += struct.pack("<IH", recid - base, position)
        rows.extend((word, uid, base, bytes(data), "") for base, data in buckets.items())
    db.executemany("INSERT INTO words(word, uid, indexbase, data, idx) VALUES (?,?,?,?,?)", rows)
    return len(postings)


def append_book(db: sqlite3.Connection, collection: int, book: Book, digest: str) -> tuple[int, int]:
    """Adds one book after everything else and commits it as a single transaction."""
    q = db.execute
    first = q("SELECT max(recid) FROM texts").fetchone()[0] + 1
    record = first - 1
    texts: list[tuple[int, str, str, str]] = []  # recid, levelname, stylename, plain
    contents: list[tuple] = []

    def add_text(levelname: str, stylename: str, plain: str) -> int:
        nonlocal record
        record += 1
        texts.append((record, levelname, stylename, plain))
        return record

    used_codes = {code for (code,) in q("SELECT DISTINCT node_code FROM contents WHERE node_code<>''")}
    code, suffix = initials(book.title), 2
    while code in used_codes:
        code, suffix = f"{initials(book.title)}{suffix}", suffix + 1
    book_record = add_text("LE_Book", "PA_Book_Title", f'<PS:"Book Title">{book.title}')
    for chapter in book.chapters:
        chapter_record = add_text("LE_Chapter", "LE_Chapter", chapter.title)
        for paragraph in chapter.paragraphs:
            add_text("", "", paragraph)
        contents.append((chapter.title, chapter_record, book_record, LEVELS["LE_Chapter"],
                         simple_title(chapter.title), "", 1, "", 0, record))
    contents.insert(0, (book.title, book_record, collection, LEVELS["LE_Book"], simple_title(book.title),
                        "", 2, code, len(book.chapters), record))
    try:
        db.executemany("INSERT INTO texts(plain, recid, showid, levelname, stylename) VALUES (?,?,NULL,?,?)",
                       [(plain, recid, level, style) for recid, level, style, plain in texts])
        db.executemany("INSERT INTO contents(title, record, parent, level, simpletitle, subtext, node_type, "
                       "node_code, node_children, next_sibling) VALUES (?,?,?,?,?,?,?,?,?,?)", contents)
        q("UPDATE contents SET next_sibling=?, node_children=node_children+1 WHERE record=?", (record, collection))
        index_words(db, [(recid, plain) for recid, _, _, plain in texts])
        q("INSERT INTO docinfo(name, valuex, idx) VALUES (?,?,?)", (MANIFEST, f"{digest}|{book.source.name}", book_record))
        db.commit()
    except BaseException:
        db.rollback()
        raise
    return first, record


def run(source: Path, output: Path, collection: str, pdfs: list[Path], rebuild: bool, settle: int,
        dry_run: bool, show_text: bool) -> None:
    if source.resolve() == output.resolve():
        raise SystemExit("--output must differ from --source; the source is never modified.")
    base = source if rebuild or not output.exists() else output
    with contextlib.closing(sqlite3.connect(f"file:{base.as_posix()}?mode=ro", uri=True)) as reader:
        hashes, titles = known_sources(reader, collection)

    pending: list[tuple[Book, str]] = []
    skipped: list[str] = []
    seen: dict[str, str] = {}
    now = time.time()
    for path in pdfs:
        if now - path.stat().st_mtime < settle:
            skipped.append(f"{path.name}: changed in the last {settle}s (still copying?), try again later")
            continue
        digest = file_hash(path)
        if digest in hashes:
            skipped.append(f"{path.name}: already in the library")
            continue
        if digest in seen:
            skipped.append(f"{path.name}: identical to {seen[digest]}")
            continue
        seen[digest] = path.name
        try:
            book = parse_pdf(path)
        except Exception as error:
            skipped.append(f"{path.name}: NOT ADDED - {error}")
            continue
        if fold(book.title) in titles:
            skipped.append(f"{path.name}: a book titled '{book.title}' is already in the library")
            continue
        titles.add(fold(book.title))
        pending.append((book, digest))
        preview(book)
        if show_text:
            for chapter in book.chapters:
                print(f"\n## {chapter.title}")
                for paragraph in chapter.paragraphs:
                    print(f"  {paragraph}")

    if skipped:
        print("\nSkipped:\n  " + "\n  ".join(skipped))
    if dry_run or not pending:
        print(f"\n{len(pending)} PDF(s) would be added." if dry_run else "\nNothing new to add.")
        return

    staging = output.with_name(output.name + ".part")
    print(f"\nCopying {base.name} ({base.stat().st_size / 1e9:.2f} GB) ...", flush=True)
    shutil.copyfile(base, staging)
    added = 0
    try:
        db = sqlite3.connect(staging)
        try:
            found = find_collection(db, collection) or create_collection(db, collection)
            for book, digest in pending:
                try:
                    first, last = append_book(db, found[0], book, digest)
                except Exception as error:
                    print(f"  FAILED {book.source.name}: {error}")
                    continue
                added += 1
                print(f"  Added {book.title} ({len(book.chapters)} chapters, records {first}-{last})", flush=True)
            print("Running quick_check ...", flush=True)
            result = db.execute("PRAGMA quick_check").fetchone()[0]
            if result != "ok":
                raise RuntimeError(f"quick_check failed: {result}")
            total = db.execute("SELECT node_children FROM contents WHERE record=?", (found[0],)).fetchone()[0]
        finally:
            db.close()
        staging.replace(output)
    except BaseException:
        staging.unlink(missing_ok=True)
        raise
    print(f"Added {added} of {len(pending)} new PDF(s); the collection now has {total} book(s). Wrote {output}")


# ----------------------------------------------------------------------- CLI


def collect_pdfs(paths: list[str]) -> list[Path]:
    found: list[Path] = []
    for value in paths:
        path = Path(value)
        if path.is_dir():
            found.extend(sorted(path.glob("*.pdf"), key=lambda p: p.name.lower()))
        elif path.suffix.lower() == ".pdf" and path.is_file():
            found.append(path)
        else:
            raise SystemExit(f"Not a PDF or folder: {path}")
    if not found:
        raise SystemExit("No PDFs found.")
    return found


def preview(book: Book) -> None:
    print(f"\n=== {book.source.name}\nBook: {book.title}")
    for chapter in book.chapters:
        words = sum(len(tokens(p)) for p in chapter.paragraphs)
        print(f"  [{len(chapter.paragraphs):3} paras, {words:5} words] {chapter.title}")


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--pdf", nargs="+", required=True, help="PDF files and/or folders of PDFs")
    parser.add_argument("--collection", required=True, help='Collection name, e.g. "Dham Yatra Guides"')
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--dry-run", action="store_true", help="Parse and print what would be added")
    parser.add_argument("--show-text", action="store_true", help="Also print every paragraph")
    parser.add_argument("--rebuild", action="store_true", help="Start again from --source instead of --output")
    parser.add_argument("--settle", type=int, default=120,
                        help="Skip PDFs modified within this many seconds (still being copied)")
    args = parser.parse_args()
    run(args.source, args.output, args.collection, collect_pdfs(args.pdf), args.rebuild, args.settle,
        args.dry_run, args.show_text)


if __name__ == "__main__":
    main()
