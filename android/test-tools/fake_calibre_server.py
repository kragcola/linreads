#!/usr/bin/env python3

from __future__ import annotations

import argparse
import base64
import io
import json
import sys
import zipfile
from dataclasses import dataclass
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse


LIBRARY_ID = "calibre-library"

PNG_1X1 = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9n7VYAAAAASUVORK5CYII="
)


@dataclass(frozen=True)
class FakeBook:
    id: int
    title: str
    author: str
    query_tokens: tuple[str, ...]
    paragraph: str
    show_on_empty_query: bool = False


def build_books() -> list[FakeBook]:
    books = [
        FakeBook(
            id=42,
            title="Remote EPUB Smoke",
            author="Calibre",
            query_tokens=("smoke", "remote"),
            paragraph="Calibre smoke paragraph proves offline reader opening after download.",
            show_on_empty_query=True,
        ),
    ]
    for index in range(1, 7):
        books.append(
            FakeBook(
                id=100 + index,
                title=f"LRU Remote {index:02d}",
                author="Calibre",
                query_tokens=(f"cache-{index:02d}", f"lru-{index:02d}"),
                paragraph=f"LRU runtime paragraph {index:02d} proves cached remote reading after download.",
            )
        )
    return books


def build_epub_bytes(book: FakeBook) -> bytes:
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as archive:
        archive.writestr(
            "mimetype",
            "application/epub+zip",
            compress_type=zipfile.ZIP_STORED,
        )
        archive.writestr(
            "META-INF/container.xml",
            """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml" />
  </rootfiles>
</container>
""",
        )
        archive.writestr(
            "OEBPS/content.opf",
            f"""<?xml version="1.0" encoding="UTF-8"?>
<package version="3.0" unique-identifier="bookid" xmlns="http://www.idpf.org/2007/opf">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">urn:uuid:readflow-calibre-{book.id}</dc:identifier>
    <dc:title>{book.title}</dc:title>
    <dc:creator>{book.author}</dc:creator>
    <meta property="dcterms:modified">2026-06-24T00:00:00Z</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav" />
    <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml" />
  </manifest>
  <spine>
    <itemref idref="chapter" />
  </spine>
</package>
""",
        )
        archive.writestr(
            "OEBPS/nav.xhtml",
            """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
  <body>
    <nav epub:type="toc">
      <ol>
        <li><a href="chapter.xhtml">Chapter 1</a></li>
      </ol>
    </nav>
  </body>
</html>
""",
        )
        archive.writestr(
            "OEBPS/chapter.xhtml",
            f"""<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
  <body>
    <h1>{book.title}</h1>
    <p>{book.paragraph}</p>
    <p>Second runtime paragraph keeps the first page non-empty for instrumentation assertions.</p>
  </body>
</html>
""",
        )
    return out.getvalue()


BOOKS = build_books()
BOOKS_BY_ID = {book.id: book for book in BOOKS}
EPUB_BYTES_BY_ID = {book.id: build_epub_bytes(book) for book in BOOKS}


class FakeCalibreHandler(BaseHTTPRequestHandler):
    server_version = "FakeCalibre/1.0"

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        if parsed.path == "/__shutdown__":
            self._write_json({"ok": True, "message": "shutting down"})
            self.server.shutdown()
            return
        if parsed.path == "/ajax/search":
            self._handle_search(parsed)
            return
        if self._handle_book_meta(parsed):
            return
        if self._handle_download(parsed):
            return
        if self._handle_cover(parsed):
            return
        self.send_error(HTTPStatus.NOT_FOUND, "not found")

    def log_message(self, fmt: str, *args: object) -> None:
        sys.stdout.write(f"[fake-calibre] {self.address_string()} - {fmt % args}\n")
        sys.stdout.flush()

    def _handle_search(self, parsed) -> None:
        params = parse_qs(parsed.query)
        query = params.get("query", [""])[0].strip().lower()
        num = int(params.get("num", ["100"])[0])
        offset = int(params.get("offset", ["0"])[0])
        matches = self._matching_books(query)
        payload = {
            "total_num": len(matches),
            "book_ids": [book.id for book in matches[offset : offset + num]],
        }
        self._write_json(payload)

    def _matching_books(self, query: str) -> list[FakeBook]:
        if not query:
            return [book for book in BOOKS if book.show_on_empty_query]
        return [
            book
            for book in BOOKS
            if any(token in query for token in book.query_tokens)
        ]

    def _handle_book_meta(self, parsed) -> bool:
        parts = parsed.path.strip("/").split("/")
        if len(parts) != 4 or parts[:2] != ["ajax", "book"] or parts[3] != LIBRARY_ID:
            return False
        book = self._book_from_parts(parts[2])
        if book is None:
            self.send_error(HTTPStatus.NOT_FOUND, "unknown book")
            return True
        self._write_json(
            {
                "id": book.id,
                "title": book.title,
                "authors": [book.author],
                "formats": ["EPUB"],
                "tags": ["readflow", "runtime"],
                "series": None,
            }
        )
        return True

    def _handle_download(self, parsed) -> bool:
        parts = parsed.path.strip("/").split("/")
        if len(parts) != 4 or parts[0] != "get" or parts[3] != LIBRARY_ID:
            return False
        if parts[1].upper() != "EPUB":
            self.send_error(HTTPStatus.NOT_FOUND, "unsupported format")
            return True
        book = self._book_from_parts(parts[2])
        if book is None:
            self.send_error(HTTPStatus.NOT_FOUND, "unknown book")
            return True
        self._write_bytes("application/epub+zip", EPUB_BYTES_BY_ID[book.id])
        return True

    def _handle_cover(self, parsed) -> bool:
        parts = parsed.path.strip("/").split("/")
        if len(parts) != 4 or parts[:2] != ["get", "cover"] or parts[3] != LIBRARY_ID:
            return False
        book = self._book_from_parts(parts[2])
        if book is None:
            self.send_error(HTTPStatus.NOT_FOUND, "unknown book")
            return True
        self._write_bytes("image/png", PNG_1X1)
        return True

    def _book_from_parts(self, raw_id: str) -> FakeBook | None:
        try:
            return BOOKS_BY_ID.get(int(raw_id))
        except ValueError:
            return None

    def _write_json(self, payload: dict[str, object]) -> None:
        data = json.dumps(payload).encode("utf-8")
        self._write_bytes("application/json; charset=utf-8", data)

    def _write_bytes(self, content_type: str, payload: bytes) -> None:
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


def main() -> int:
    parser = argparse.ArgumentParser(description="Fake Calibre Content Server for AVD runtime smoke.")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8081)
    args = parser.parse_args()

    httpd = ThreadingHTTPServer((args.host, args.port), FakeCalibreHandler)
    print(f"[fake-calibre] listening on http://{args.host}:{args.port}", flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("[fake-calibre] shutting down", flush=True)
    finally:
        httpd.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
