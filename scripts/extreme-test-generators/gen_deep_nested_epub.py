#!/usr/bin/env python3
"""生成深层嵌套 HTML 的 EPUB 测试文件"""

import argparse
import zipfile
from pathlib import Path


def generate_nested_html(depth: int) -> str:
    """生成嵌套指定深度的 HTML"""
    opening_tags = "".join(f"<div id='level{i}'>" for i in range(depth))
    content = f"<p>这是嵌套 {depth} 层的内容</p>"
    closing_tags = "</div>" * depth

    return f"""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
    <title>Deep Nested Test</title>
</head>
<body>
{opening_tags}
{content}
{closing_tags}
</body>
</html>"""


def generate_epub(depth: int, output: Path):
    """生成 EPUB 文件"""
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as zf:
        # mimetype (必须是第一个文件，不压缩)
        zf.writestr('mimetype', 'application/epub+zip', compress_type=zipfile.ZIP_STORED)

        # META-INF/container.xml
        zf.writestr('META-INF/container.xml', '''<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>''')

        # OEBPS/content.opf
        zf.writestr('OEBPS/content.opf', f'''<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>Deep Nested HTML Test (depth={depth})</dc:title>
    <dc:language>zh</dc:language>
    <dc:identifier id="uid">deep-nested-{depth}</dc:identifier>
  </metadata>
  <manifest>
    <item id="chapter1" href="chapter1.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="chapter1"/>
  </spine>
</package>''')

        # OEBPS/chapter1.xhtml
        zf.writestr('OEBPS/chapter1.xhtml', generate_nested_html(depth))

    print(f"✅ 已生成: {output} (嵌套深度: {depth})")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='生成深层嵌套 HTML 的 EPUB')
    parser.add_argument('--depth', type=int, default=100, help='嵌套深度')
    parser.add_argument('--output', type=Path, required=True, help='输出文件路径')
    args = parser.parse_args()

    generate_epub(args.depth, args.output)
