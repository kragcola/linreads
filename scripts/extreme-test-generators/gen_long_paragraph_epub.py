#!/usr/bin/env python3
"""生成超长单段落 EPUB (无换行)"""

import argparse
import zipfile
from pathlib import Path


def generate_epub(chars: int, output: Path):
    """生成包含超长单段落的 EPUB"""

    # 生成连续文本（中英文混合，无空格）
    repeated_text = "这是一段超长的连续文本没有任何换行符号用于测试TextView的极限渲染能力ThisisEnglishtext" * (chars // 50)
    long_paragraph = repeated_text[:chars]

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
    <dc:title>超长段落测试 ({chars} 字)</dc:title>
    <dc:language>zh</dc:language>
    <dc:identifier id="uid">long-para-{chars}</dc:identifier>
  </metadata>
  <manifest>
    <item id="c1" href="chapter.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="c1"/>
  </spine>
</package>''')

        zf.writestr('chapter.xhtml', f'''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>Long Paragraph</title></head>
<body>
<h1>超长段落测试</h1>
<p>{long_paragraph}</p>
</body>
</html>''')

    print(f"✅ 已生成: {output} (字符数: {chars}, 文件大小: {output.stat().st_size} bytes)")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='生成超长单段落 EPUB')
    parser.add_argument('--chars', type=int, default=50000, help='段落字符数')
    parser.add_argument('--output', type=Path, required=True, help='输出文件路径')
    args = parser.parse_args()

    generate_epub(args.chars, args.output)
