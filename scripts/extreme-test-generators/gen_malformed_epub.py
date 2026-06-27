#!/usr/bin/env python3
"""生成畸形 EPUB (缺少标准组件)"""

import argparse
import zipfile
from pathlib import Path


def generate_epub(missing_components: list[str], output: Path):
    """生成缺少指定组件的 EPUB"""

    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as zf:
        zf.writestr('mimetype', 'application/epub+zip', compress_type=zipfile.ZIP_STORED)

        zf.writestr('META-INF/container.xml', '''<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>''')

        # 根据缺失组件调整 OPF
        has_nav = 'nav' not in missing_components
        has_toc = 'toc' not in missing_components
        has_spine = 'spine' not in missing_components

        manifest_items = '<item id="c1" href="chapter.xhtml" media-type="application/xhtml+xml"/>'
        if has_nav:
            manifest_items += '\n    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>'
        if has_toc:
            manifest_items += '\n    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>'

        spine_content = '<itemref idref="c1"/>' if has_spine else ''
        spine_toc = f' toc="ncx"' if has_toc else ''

        zf.writestr('content.opf', f'''<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>畸形 EPUB (缺:{','.join(missing_components)})</dc:title>
    <dc:language>zh</dc:language>
    <dc:identifier id="uid">malformed-{'-'.join(missing_components)}</dc:identifier>
  </metadata>
  <manifest>
    {manifest_items}
  </manifest>
  <spine{spine_toc}>
    {spine_content}
  </spine>
</package>''')

        zf.writestr('chapter.xhtml', '''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>Test Chapter</title></head>
<body>
<h1>测试章节</h1>
<p>这是一个畸形的 EPUB 文件，用于测试解析器的容错能力。</p>
<p>缺少的组件应该被优雅地处理，不应导致崩溃。</p>
</body>
</html>''')

        # 有选择地添加 nav 和 toc
        if has_nav:
            zf.writestr('nav.xhtml', '''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>Navigation</title></head>
<body>
<nav epub:type="toc">
  <ol>
    <li><a href="chapter.xhtml">Chapter 1</a></li>
  </ol>
</nav>
</body>
</html>''')

        if has_toc:
            zf.writestr('toc.ncx', '''<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="malformed"/>
  </head>
  <docTitle><text>Malformed EPUB</text></docTitle>
  <navMap>
    <navPoint id="c1">
      <navLabel><text>Chapter 1</text></navLabel>
      <content src="chapter.xhtml"/>
    </navPoint>
  </navMap>
</ncx>''')

    print(f"✅ 已生成: {output} (缺少组件: {', '.join(missing_components)})")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='生成畸形 EPUB')
    parser.add_argument('--missing', type=str, required=True, help='缺少的组件 (逗号分隔: toc,nav,spine)')
    parser.add_argument('--output', type=Path, required=True, help='输出文件路径')
    args = parser.parse_args()

    missing = [c.strip() for c in args.missing.split(',')]
    generate_epub(missing, args.output)
