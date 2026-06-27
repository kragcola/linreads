#!/usr/bin/env python3
"""
生成中英文混排测试EPUB
测试字体回退、行高、标点等排版细节
"""

import argparse
import zipfile
from pathlib import Path


def generate_epub(output: Path):
    """生成中英文混排测试EPUB"""

    test_content = '''<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>排版细节测试</title></head>
<body>

<h1>Chapter 1: 中英文混排测试</h1>

<h2>1.1 基础混排</h2>
<p>这是一段中英文混合的文字This is mixed Chinese and English text这里测试字体是否协调以及line height是否统一。</p>

<h2>1.2 长单词换行</h2>
<p>这里有一个很长的英文单词：internationalization 应该如何处理？还有 supercalifragilisticexpialidocious 这样的超长词。</p>

<h2>1.3 标点符号</h2>
<p>中文标点：，。！？；：""''（）【】《》</p>
<p>英文标点: , . ! ? ; : "" '' () [] &lt;&gt;</p>
<p>混合：这是一句话，包含English content。Another sentence！测试标点？</p>

<h2>1.4 数字混排</h2>
<p>价格：$99.99 USD，折合人民币¥688.88。时间：2026年6月27日 15:30:45。</p>
<p>百分比：50% vs 50％，温度：25°C vs 25℃。</p>

<h2>1.5 Emoji测试</h2>
<p>常用emoji：😀😃😄😁😆😅🤣😂🙂🙃😉😊😇</p>
<p>书籍相关：📚📖📕📗📘📙📔📒📓📝📄📃</p>
<p>混合：今天天气真好🌞，适合reading📚some books!</p>

<h1>Chapter 2: 特殊排版元素</h1>

<h2>2.1 代码块</h2>
<pre><code>function greet(name) {
  return `Hello, ${name}!`;
}

const message = greet("World");
console.log(message);
</code></pre>

<p>内联代码：使用 <code>console.log()</code> 打印信息。</p>

<h2>2.2 引用块</h2>
<blockquote>
<p>这是一段引用文字，应该有特殊的样式和缩进。</p>
<p>— 引用来源</p>
</blockquote>

<h2>2.3 列表</h2>
<ul>
  <li>无序列表项1：包含English content</li>
  <li>无序列表项2：包含emoji 🎨</li>
  <li>嵌套列表：
    <ul>
      <li>子项A</li>
      <li>子项B with English</li>
    </ul>
  </li>
</ul>

<ol>
  <li>有序列表项1</li>
  <li>有序列表项2 with mixed content</li>
  <li>有序列表项3</li>
</ol>

<h2>2.4 表格</h2>
<table border="1">
  <thead>
    <tr>
      <th>列1</th>
      <th>Column 2</th>
      <th>混合列</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td>数据1</td>
      <td>Data 2</td>
      <td>Mixed数据</td>
    </tr>
    <tr>
      <td>中文内容</td>
      <td>English content</td>
      <td>123.45</td>
    </tr>
    <tr>
      <td colspan="3">合并单元格 Merged cell</td>
    </tr>
  </tbody>
</table>

<h2>2.5 上标下标</h2>
<p>数学公式：E=mc<sup>2</sup></p>
<p>化学式：H<sub>2</sub>O, CO<sub>2</sub></p>
<p>脚注引用：这是正文<sup>[1]</sup>。</p>

<h2>2.6 文字样式</h2>
<p><strong>粗体文字 Bold text 混合粗体</strong></p>
<p><em>斜体文字 Italic text 混合斜体</em></p>
<p><u>下划线文字 Underlined text</u></p>
<p><del>删除线文字 Strikethrough text</del></p>
<p><mark>高亮文字 Highlighted text</mark></p>

<h1>Chapter 3: 长文本测试</h1>

<h2>3.1 密集段落</h2>
<p>这是一段很长的密集文字，用于测试长时间阅读的舒适度。这段文字包含了中文和English的混合内容，旨在模拟真实的阅读场景。在实际阅读过程中，用户可能会遇到各种各样的排版情况，包括但不限于：不同长度的段落、不同语言的混合、各种标点符号的使用、数字和特殊字符的穿插等等。因此，我们需要确保在所有这些情况下，排版都能保持清晰、舒适、易读。The typography should remain consistent regardless of the content type. Users should not experience any jarring transitions or inconsistencies when switching between Chinese and English text. Line height, font size, letter spacing, and all other typographic parameters should work harmoniously together to create a pleasant reading experience.</p>

<h2>3.2 超长单段</h2>
<p>''' + '这是一个非常长的段落，' * 100 + '''用于测试超长段落的处理能力。</p>

<h2>3.3 多段连续</h2>
<p>第一段文字。</p>
<p>第二段文字，包含English content。</p>
<p>第三段文字，测试段间距是否合适。</p>
<p>第四段文字。</p>
<p>第五段文字，最后一段。</p>

<h1>Chapter 4: 边界情况</h1>

<h2>4.1 罕用字</h2>
<p>CJK扩展字符：𠮷野家（吉的异体字）、𩸽（hokke鱼）、𪚥（龙的异体）</p>

<h2>4.2 特殊空白</h2>
<p>全角空格：中　文　空　格</p>
<p>半角空格：English space test</p>
<p>零宽空格：test​invisible​space</p>

<h2>4.3 方向标记</h2>
<p>从左到右：LTR text 从左到右</p>
<p>阿拉伯文（示例）：مرحبا العالم</p>

<h2>4.4 组合字符</h2>
<p>带音标的字母：café, naïve, Zürich</p>
<p>组合符号：👨‍👩‍👧‍👦 (家庭emoji)</p>

</body>
</html>'''

    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as zf:
        zf.writestr('mimetype', 'application/epub+zip', compress_type=zipfile.ZIP_STORED)

        zf.writestr('META-INF/container.xml', '''<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>''')

        zf.writestr('content.opf', '''<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>排版细节测试</dc:title>
    <dc:language>zh</dc:language>
    <dc:identifier id="uid">typography-detail-test</dc:identifier>
  </metadata>
  <manifest>
    <item id="ch1" href="chapter.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine>
    <itemref idref="ch1"/>
  </spine>
</package>''')

        zf.writestr('chapter.xhtml', test_content)

    print(f"✅ 已生成: {output}")
    print(f"   包含测试: 中英文混排、特殊元素、长文本、边界情况")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='生成排版细节测试EPUB')
    parser.add_argument('--output', type=Path, required=True, help='输出文件路径')
    args = parser.parse_args()

    generate_epub(args.output)
