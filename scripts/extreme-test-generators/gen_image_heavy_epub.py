#!/usr/bin/env python3
"""生成包含大量图片的 EPUB 测试文件"""

import argparse
import base64
import zipfile
from pathlib import Path


def generate_test_image_base64(size_kb: int = 10) -> str:
    """生成指定大小的测试图片 (base64)"""
    # 1x1 PNG 的 base64 (最小)
    tiny_png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="

    # 重复填充到目标大小
    target_bytes = size_kb * 1024
    repeat_count = target_bytes // len(tiny_png)
    return tiny_png * repeat_count


def generate_epub(image_count: int, image_size_kb: int, output: Path):
    """生成包含大量图片的 EPUB"""

    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as zf:
        zf.writestr('mimetype', 'application/epub+zip', compress_type=zipfile.ZIP_STORED)

        zf.writestr('META-INF/container.xml', '''<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>''')

        # 生成 manifest 条目
        manifest_items = ['<item id="c1" href="chapter.xhtml" media-type="application/xhtml+xml"/>']

        # 生成图片内容 HTML
        image_html = []
        for i in range(image_count):
            img_data = generate_test_image_base64(image_size_kb)
            image_html.append(f'<p>图片 {i+1}:</p>')
            image_html.append(f'<img src="data:image/png;base64,{img_data}" alt="Test Image {i+1}"/>')

        zf.writestr('content.opf', f'''<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>图片密集 EPUB ({image_count} 张)</dc:title>
    <dc:language>zh</dc:language>
    <dc:identifier id="uid">image-heavy-{image_count}</dc:identifier>
  </metadata>
  <manifest>
    {''.join(manifest_items)}
  </manifest>
  <spine>
    <itemref idref="c1"/>
  </spine>
</package>''')

        zf.writestr('chapter.xhtml', f'''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>Image Heavy Chapter</title></head>
<body>
<h1>图片密集测试</h1>
<p>本章包含 {image_count} 张内联 Base64 图片，用于测试内存管理。</p>
{''.join(image_html)}
</body>
</html>''')

    file_size_mb = output.stat().st_size / (1024 * 1024)
    print(f"✅ 已生成: {output} (图片数: {image_count}, 文件大小: {file_size_mb:.2f} MB)")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='生成图片密集 EPUB')
    parser.add_argument('--images', type=int, default=50, help='图片数量')
    parser.add_argument('--image-size', type=int, default=10, help='单张图片大小 (KB)')
    parser.add_argument('--output', type=Path, required=True, help='输出文件路径')
    args = parser.parse_args()

    generate_epub(args.images, args.image_size, args.output)
