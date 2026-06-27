#!/usr/bin/env python3
"""生成最小化 EPUB (边界测试)"""

import argparse
import zipfile
from pathlib import Path


def generate_epub(paragraphs: int, output: Path):
    """生成只有少量段落的最小 EPUB"""

    content_paras = "\n".join(f"<p>这是第 {i+1} 段。</p>" for i in range(paragraphs))

    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as zf:
        zf.writestr('mimetype', 'application/epub+zip', compress_type=zipfile.ZIP_STORED)

        zf.writestr('META-INF/container.xml', '''<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>''')

        zf.writestr('content.opf', f'''<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>极小 EPUB ({paragraphs} 段)</dc:title>
    <dc:language>zh</dc:language>
    <dc:identifier id="uid">minimal-{paragraphs}</dc:identifier>
  </metadata>
  <manifest>
    <item id="c1" href="ch.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="c1"/>
  </spine>
</package>''')

        zf.writestr('ch.xhtml', f'''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>Chapter</title></head>
<body>
<h1>测试章节</h1>
{content_paras}
</body>
</html>''')

    print(f"✅ 已生成: {output} (段落数: {paragraphs}, 大小: {output.stat().st_size} bytes)")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='生成最小化 EPUB')
    parser.add_argument('--paragraphs', type=int, default=3, help='段落数量')
    parser.add_argument('--output', type=Path, required=True, help='输出文件路径')
    args = parser.parse_args()

    generate_epub(args.paragraphs, args.output)
