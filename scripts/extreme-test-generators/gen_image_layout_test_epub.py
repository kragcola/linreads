#!/usr/bin/env python3
"""
生成图片布局测试专用EPUB
用于测试横竖屏图片显示问题
"""

import argparse
import zipfile
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import io
import base64


def create_test_image(width, height, text, color='#4CAF50'):
    """创建带文字标记的测试图片"""
    img = Image.new('RGB', (width, height), color)
    draw = ImageDraw.Draw(img)

    # 绘制尺寸标记
    try:
        font = ImageFont.truetype('/System/Library/Fonts/Helvetica.ttc', 40)
    except:
        font = ImageFont.load_default()

    label = f"{width}x{height}\n{text}"
    bbox = draw.textbbox((0, 0), label, font=font)
    text_width = bbox[2] - bbox[0]
    text_height = bbox[3] - bbox[1]

    x = (width - text_width) // 2
    y = (height - text_height) // 2

    # 绘制背景
    draw.rectangle([x-10, y-10, x+text_width+10, y+text_height+10], fill='white')
    draw.text((x, y), label, fill='black', font=font)

    # 绘制边框
    draw.rectangle([0, 0, width-1, height-1], outline='black', width=5)

    return img


def image_to_base64(img):
    """将PIL图片转换为base64"""
    buffer = io.BytesIO()
    img.save(buffer, format='PNG')
    buffer.seek(0)
    return base64.b64encode(buffer.read()).decode('utf-8')


def generate_epub(output: Path):
    """生成图片布局测试EPUB"""

    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as zf:
        # mimetype
        zf.writestr('mimetype', 'application/epub+zip', compress_type=zipfile.ZIP_STORED)

        # container.xml
        zf.writestr('META-INF/container.xml', '''<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>''')

        # OPF
        zf.writestr('OEBPS/content.opf', '''<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>图片布局测试</dc:title>
    <dc:language>zh</dc:language>
    <dc:identifier id="uid">image-layout-test</dc:identifier>
  </metadata>
  <manifest>
    <item id="ch1" href="ch1-portrait.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="ch2-landscape.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch3" href="ch3-square.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch4" href="ch4-inline.xhtml" media-type="application/xhtml+xml"/>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
    <itemref idref="ch3"/>
    <itemref idref="ch4"/>
  </spine>
</package>''')

        # 生成测试图片
        portrait_img = create_test_image(1080, 1920, "竖图\nPortrait", '#4CAF50')
        landscape_img = create_test_image(1920, 1080, "横图\nLandscape", '#2196F3')
        square_img = create_test_image(1000, 1000, "方图\nSquare", '#FF9800')
        small_img = create_test_image(300, 400, "小图\nSmall", '#E91E63')

        portrait_b64 = image_to_base64(portrait_img)
        landscape_b64 = image_to_base64(landscape_img)
        square_b64 = image_to_base64(square_img)
        small_b64 = image_to_base64(small_img)

        # Chapter 1: 竖图测试
        zf.writestr('OEBPS/ch1-portrait.xhtml', f'''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>竖图测试</title></head>
<body>
<h1>Chapter 1: 竖图测试 (1080x1920)</h1>
<p>说明：这是一张竖版图片，请在横屏和竖屏模式下对比显示大小。</p>
<p><strong>预期</strong>：横屏时应充分利用屏幕宽度，不应过小。</p>
<figure>
  <img src="data:image/png;base64,{portrait_b64}" alt="Portrait Image"/>
  <figcaption>竖图：1080x1920</figcaption>
</figure>
<p>图片下方文字：用于确认图片是否被正确包含在页面中。</p>
</body>
</html>''')

        # Chapter 2: 横图测试
        zf.writestr('OEBPS/ch2-landscape.xhtml', f'''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>横图测试</title></head>
<body>
<h1>Chapter 2: 横图测试 (1920x1080)</h1>
<p>说明：这是一张横版图片，适合横屏显示。</p>
<p><strong>预期</strong>：横屏时应占据大部分屏幕宽度。</p>
<figure>
  <img src="data:image/png;base64,{landscape_b64}" alt="Landscape Image"/>
  <figcaption>横图：1920x1080</figcaption>
</figure>
<p>图片下方文字。</p>
</body>
</html>''')

        # Chapter 3: 方图测试
        zf.writestr('OEBPS/ch3-square.xhtml', f'''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>方图测试</title></head>
<body>
<h1>Chapter 3: 方图测试 (1000x1000)</h1>
<p>说明：正方形图片，测试宽高比保持。</p>
<figure>
  <img src="data:image/png;base64,{square_b64}" alt="Square Image"/>
  <figcaption>方图：1000x1000</figcaption>
</figure>
<p>图片下方文字。</p>
</body>
</html>''')

        # Chapter 4: 内联图片测试
        zf.writestr('OEBPS/ch4-inline.xhtml', f'''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>内联图片测试</title></head>
<body>
<h1>Chapter 4: 内联图片测试</h1>
<p>这是一段文字，中间有一个小图 <img src="data:image/png;base64,{small_b64}" alt="inline" style="vertical-align: middle;"/> 内联显示。</p>
<p>检查点：</p>
<ul>
  <li>内联图片是否与文字对齐</li>
  <li>内联图片大小是否合理</li>
  <li>横竖屏切换后内联图片是否正常</li>
</ul>
<p>下方是块级图片：</p>
<p><img src="data:image/png;base64,{small_b64}" alt="block"/></p>
<p>块级图片下方文字。</p>
</body>
</html>''')

        # Nav
        zf.writestr('OEBPS/nav.xhtml', '''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>Navigation</title></head>
<body>
<nav epub:type="toc">
  <h1>目录</h1>
  <ol>
    <li><a href="ch1-portrait.xhtml">Chapter 1: 竖图测试</a></li>
    <li><a href="ch2-landscape.xhtml">Chapter 2: 横图测试</a></li>
    <li><a href="ch3-square.xhtml">Chapter 3: 方图测试</a></li>
    <li><a href="ch4-inline.xhtml">Chapter 4: 内联图片测试</a></li>
  </ol>
</nav>
</body>
</html>''')

    size_mb = output.stat().st_size / (1024 * 1024)
    print(f"✅ 已生成: {output}")
    print(f"   文件大小: {size_mb:.2f} MB")
    print(f"   包含图片: 竖图(1080x1920), 横图(1920x1080), 方图(1000x1000), 小图(300x400)")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='生成图片布局测试EPUB')
    parser.add_argument('--output', type=Path, required=True, help='输出文件路径')
    args = parser.parse_args()

    generate_epub(args.output)
