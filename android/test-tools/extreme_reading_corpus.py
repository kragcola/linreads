#!/usr/bin/env python3

from __future__ import annotations

import argparse
import html
import json
import time
import zipfile
from dataclasses import dataclass
from pathlib import Path


CORPUS_ID = "rf-mr-extreme-20260627"


@dataclass(frozen=True)
class CorpusFile:
    path: Path
    kind: str
    description: str


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Generate shared extreme-reading corpus for LinReads/MoonReader comparison.",
    )
    parser.add_argument(
        "--out",
        default="/tmp/readflow-moonreader-linreads-corpus",
        help="Output directory. Defaults to /tmp/readflow-moonreader-linreads-corpus.",
    )
    args = parser.parse_args()

    out_dir = Path(args.out).expanduser().resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    files = [
        CorpusFile(
            path=write_extreme_epub(out_dir / f"{CORPUS_ID}.epub"),
            kind="epub",
            description=(
                "EPUB 3 stress book: invisible spacer-only paragraphs, CJK micro "
                "paragraphs, mixed safe styles, long unspaced CJK, Latin micro "
                "paragraphs, and short later spines."
            ),
        ),
        CorpusFile(
            path=write_extreme_txt(out_dir / f"{CORPUS_ID}-utf8.txt"),
            kind="txt",
            description=(
                "UTF-8 long TXT: repeated CJK hard-wrap lines, ASCII probes, "
                "near-tail anchors, emoji, zero-width/invisible spacer probes."
            ),
        ),
        CorpusFile(
            path=write_extreme_pdf(out_dir / f"{CORPUS_ID}.pdf"),
            kind="pdf",
            description=(
                "20-page synthetic PDF with stable page markers and dense line "
                "blocks for first-open, page-turn, and memory comparison."
            ),
        ),
    ]
    manifest = {
        "corpus_id": CORPUS_ID,
        "generated_at_epoch": int(time.time()),
        "comparison_target": ["LinReads Android", "Moon+ Reader Pro Android"],
        "files": [
            {
                "name": file.path.name,
                "kind": file.kind,
                "size_bytes": file.path.stat().st_size,
                "description": file.description,
            }
            for file in files
        ],
        "expected_probe_markers": {
            "epub": [
                "RFMR-CJK-001",
                "RFMR-LONG-001",
                "RFMR-TAIL-001",
                "RFMR-LATIN-001",
                "RFMR-SPINE-06-10",
            ],
            "txt": ["RFMR-TXT-000001", "RFMR-TXT-NEAR-TAIL", "RFMR-TXT-END"],
            "pdf": ["RFMR PDF PAGE 001", "RFMR PDF PAGE 020"],
        },
        "measurement_boundaries": [
            "Use the exact same files for both apps.",
            "Record device model, density, display size, app build/version, and input route.",
            "Do not compare AVD numbers with physical-device numbers as equivalent evidence.",
        ],
    }
    manifest_path = out_dir / "manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    write_readme(out_dir / "README.md", files)
    print(json.dumps({"out": str(out_dir), "files": [file.path.name for file in files]}, ensure_ascii=False))


def write_extreme_epub(path: Path) -> Path:
    chapters = build_epub_chapters()
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("mimetype", "application/epub+zip", compress_type=zipfile.ZIP_STORED)
        archive.writestr(
            "META-INF/container.xml",
            """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
""",
        )
        manifest_items = "\n".join(
            f'    <item id="c{index}" href="{name}" media-type="application/xhtml+xml"/>'
            for index, (name, _title, _body) in enumerate(chapters, start=1)
        )
        spine_items = "\n".join(
            f'    <itemref idref="c{index}"/>'
            for index in range(1, len(chapters) + 1)
        )
        archive.writestr(
            "OEBPS/content.opf",
            f"""<?xml version="1.0" encoding="UTF-8"?>
<package version="3.0" unique-identifier="bookid" xmlns="http://www.idpf.org/2007/opf">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">urn:uuid:{CORPUS_ID}</dc:identifier>
    <dc:title>Readflow MoonReader Extreme Corpus</dc:title>
    <dc:creator>Readflow Test Tools</dc:creator>
    <dc:language>zh-CN</dc:language>
    <meta property="dcterms:modified">2026-06-27T00:00:00Z</meta>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
{manifest_items}
  </manifest>
  <spine>
{spine_items}
  </spine>
</package>
""",
        )
        nav_items = "\n".join(
            f'        <li><a href="{name}">{html.escape(title)}</a></li>'
            for name, title, _body in chapters
        )
        archive.writestr(
            "OEBPS/nav.xhtml",
            f"""<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
  <head><title>Navigation</title></head>
  <body>
    <nav epub:type="toc">
      <ol>
{nav_items}
      </ol>
    </nav>
  </body>
</html>
""",
        )
        for name, title, body in chapters:
            archive.writestr(
                f"OEBPS/{name}",
                f"""<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
  <head>
    <title>{html.escape(title)}</title>
    <style>
      body {{ line-height: 1.7; }}
      blockquote {{ margin-left: 1.4em; }}
      pre {{ white-space: pre-wrap; }}
      table {{ border-collapse: collapse; }}
      td {{ border: 1px solid #999; padding: 2px 4px; }}
    </style>
  </head>
  <body>
{body}
  </body>
</html>
""",
            )
    return path


def build_epub_chapters() -> list[tuple[str, str, str]]:
    invisible_spacers = [
        "&nbsp;",
        "　",
        "&#8203;",
        "&#65279;",
        "&#8288;",
        "&#8204;&#8205;",
        "<span>&nbsp;&#8203;&#65279;&#8288;</span>",
    ]
    cjk_fragments = [
        f"RFMR-CJK-{index:03d}：“还在。”"
        for index in range(1, 49)
    ]
    chapter_1 = ["<h1>RFMR Chapter 1 Invisible Spacer Stress</h1>"]
    chapter_1.extend(f"<p>{spacer}</p>" for spacer in invisible_spacers)
    for index, fragment in enumerate(cjk_fragments):
        chapter_1.append(f"<p>{invisible_spacers[index % len(invisible_spacers)]}</p>")
        chapter_1.append(f"<p><span>{html.escape(fragment)}</span></p>")
        chapter_1.append(f"<p>{invisible_spacers[(index + 3) % len(invisible_spacers)]}</p>")

    chapter_2 = ["<h1>RFMR Chapter 2 Mixed Safe Styles</h1>"]
    for index in range(1, 73):
        marker = f"RFMR-MIX-{index:03d}"
        if index % 9 == 0:
            chapter_2.append("<p><span> </span></p>")
            chapter_2.append("<hr/>")
        chapter_2.append(
            [
                f"<p><span>{marker}</span><br/><span>“嗯。”</span></p>",
                f"<p><em>{marker}：“不行。”</em></p>",
                f"<blockquote><p><strong>{marker}：门响。</strong></p></blockquote>",
                f"<ul><li><span>{marker}：钥匙。</span></li></ul>",
                f"<p><ruby>{marker}<rt>noise</rt></ruby>：雨落。</p>",
                f"<pre>{marker}: pre block keeps wrapped text stable.</pre>",
            ][index % 6],
        )

    long_segments = [
        f"RFMR-LONG-{index:03d}窗外雨声贴着玻璃往下滑灯影落在旧书页"
        for index in range(1, 49)
    ]
    chapter_3 = [
        "<h1>RFMR Chapter 3 Long Unspaced CJK</h1>",
        f"<p>{''.join(long_segments)}</p>",
    ]
    chapter_3.extend(
        f"<p>RFMR-TAIL-{index:03d}：尾声仍亮着。</p>"
        for index in range(1, 25)
    )

    chapter_4 = ["<h1>RFMR Chapter 4 Latin And Table Stress</h1>"]
    chapter_4.extend(
        f"<p>RFMR-LATIN-{index:03d}: short Latin paragraph for mixed-script packing.</p>"
        for index in range(1, 91)
    )
    chapter_4.append(
        "<table><tr><td>RFMR-TABLE-A</td><td>dense cell</td></tr>"
        "<tr><td>RFMR-TABLE-B</td><td>second row</td></tr></table>",
    )

    short_spine_chapters: list[tuple[str, str, str]] = []
    for chapter_index in range(5, 11):
        body = [f"<h2>RFMR Short Spine {chapter_index:02d}</h2>"]
        body.extend(
            f"<p>RFMR-SPINE-{chapter_index:02d}-{marker_index:02d}：短章短句。</p>"
            for marker_index in range(1, 11)
        )
        short_spine_chapters.append(
            (
                f"chapter-{chapter_index}.xhtml",
                f"RFMR Short Spine {chapter_index:02d}",
                "\n".join(body),
            ),
        )

    return [
        ("chapter-1.xhtml", "RFMR Invisible Spacer Stress", "\n".join(chapter_1)),
        ("chapter-2.xhtml", "RFMR Mixed Safe Styles", "\n".join(chapter_2)),
        ("chapter-3.xhtml", "RFMR Long Unspaced CJK", "\n".join(chapter_3)),
        ("chapter-4.xhtml", "RFMR Latin Table Stress", "\n".join(chapter_4)),
        *short_spine_chapters,
    ]


def write_extreme_txt(path: Path) -> Path:
    lines: list[str] = []
    for index in range(1, 9001):
        if index % 1200 == 0:
            lines.append(
                f"RFMR-TXT-{index:06d} \u200b\ufeff\u2060 invisible spacer probe before and after real text.",
            )
        elif index % 997 == 0:
            lines.append(
                f"RFMR-TXT-{index:06d} emoji probe 👩‍💻 keeps grapheme selection honest.",
            )
        else:
            lines.append(
                f"RFMR-TXT-{index:06d} 窗外雨声贴着玻璃往下滑，灯影落在旧书页，短句仍应连续阅读。",
            )
    lines.append("RFMR-TXT-NEAR-TAIL near-tail anchor for TOC/search/restore comparison.")
    lines.append("RFMR-TXT-END final anchor.")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return path


def write_extreme_pdf(path: Path) -> Path:
    objects: list[bytes] = []

    def add_object(payload: str | bytes) -> int:
        if isinstance(payload, str):
            payload = payload.encode("ascii")
        objects.append(payload)
        return len(objects)

    catalog_id = add_object("<< /Type /Catalog /Pages 2 0 R >>")
    pages_placeholder_id = add_object(b"")
    font_id = add_object("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    page_ids: list[int] = []
    for page in range(1, 21):
        lines = [f"RFMR PDF PAGE {page:03d}"]
        lines.extend(
            f"RFMR-PDF-{page:03d}-{line:02d} dense line block for page-turn and render comparison."
            for line in range(1, 25)
        )
        content = pdf_text_stream(lines)
        content_id = add_object(
            f"<< /Length {len(content)} >>\nstream\n".encode("ascii")
            + content
            + b"\nendstream",
        )
        page_id = add_object(
            f"<< /Type /Page /Parent {pages_placeholder_id} 0 R "
            f"/MediaBox [0 0 612 792] "
            f"/Resources << /Font << /F1 {font_id} 0 R >> >> "
            f"/Contents {content_id} 0 R >>",
        )
        page_ids.append(page_id)
    kids = " ".join(f"{page_id} 0 R" for page_id in page_ids)
    objects[pages_placeholder_id - 1] = (
        f"<< /Type /Pages /Kids [{kids}] /Count {len(page_ids)} >>".encode("ascii")
    )

    output = bytearray()
    output.extend(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = [0]
    for index, payload in enumerate(objects, start=1):
        offsets.append(len(output))
        output.extend(f"{index} 0 obj\n".encode("ascii"))
        output.extend(payload)
        output.extend(b"\nendobj\n")
    xref_offset = len(output)
    output.extend(f"xref\n0 {len(objects) + 1}\n".encode("ascii"))
    output.extend(b"0000000000 65535 f \n")
    for offset in offsets[1:]:
        output.extend(f"{offset:010d} 00000 n \n".encode("ascii"))
    output.extend(
        f"trailer\n<< /Size {len(objects) + 1} /Root {catalog_id} 0 R >>\n"
        f"startxref\n{xref_offset}\n%%EOF\n".encode("ascii"),
    )
    path.write_bytes(bytes(output))
    return path


def pdf_text_stream(lines: list[str]) -> bytes:
    commands = ["BT", "/F1 11 Tf", "14 TL", "54 748 Td"]
    for index, line in enumerate(lines):
        if index > 0:
            commands.append("T*")
        commands.append(f"({pdf_escape(line)}) Tj")
    commands.append("ET")
    return "\n".join(commands).encode("ascii")


def pdf_escape(text: str) -> str:
    return text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")


def write_readme(path: Path, files: list[CorpusFile]) -> None:
    rows = "\n".join(
        f"| `{file.path.name}` | {file.kind.upper()} | {file.description} |"
        for file in files
    )
    path.write_text(
        f"""# LinReads / MoonReader Extreme Reading Corpus

Corpus id: `{CORPUS_ID}`

Use these exact files for both apps when comparing functional reading quality,
performance, accessibility, and failure behavior.

| File | Kind | Purpose |
| --- | --- | --- |
{rows}

Minimum run:

1. Install the target app on the same device.
2. Open each file through the system file entry path (`ACTION_VIEW` or file manager).
3. Record cold launch time, first visible content marker, page/scroll sampling, PSS, gfxinfo, screenshots, and logcat.
4. Reset app data before switching apps if the comparison needs first-run behavior.

Do not compare AVD and physical-device results as the same evidence tier.
""",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
