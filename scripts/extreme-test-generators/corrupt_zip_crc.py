#!/usr/bin/env python3
"""破坏 EPUB ZIP 文件的 CRC 校验"""

import argparse
import shutil
import zipfile
from pathlib import Path


def corrupt_zip_crc(input_epub: Path, output_epub: Path):
    """复制 EPUB 并破坏 ZIP CRC"""

    # 先复制文件
    shutil.copy(input_epub, output_epub)

    # 读取并验证原文件
    try:
        with zipfile.ZipFile(input_epub, 'r') as zf:
            print(f"原始文件包含 {len(zf.namelist())} 个条目")
    except zipfile.BadZipFile as e:
        print(f"⚠️  输入文件已损坏: {e}")
        return

    # 简单破坏：截断文件
    with open(output_epub, 'r+b') as f:
        f.seek(0, 2)  # 移到文件末尾
        size = f.tell()
        # 截断最后 20% 的数据
        truncate_at = int(size * 0.8)
        f.truncate(truncate_at)

    print(f"✅ 已生成破损文件: {output_epub}")
    print(f"   原始大小: {input_epub.stat().st_size} bytes")
    print(f"   破损大小: {output_epub.stat().st_size} bytes")

    # 验证确实已损坏
    try:
        with zipfile.ZipFile(output_epub, 'r') as zf:
            zf.testzip()
        print("⚠️  文件可能未成功破坏")
    except Exception as e:
        print(f"✅ 确认已破坏: {type(e).__name__}")


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='破坏 EPUB ZIP CRC')
    parser.add_argument('input', type=Path, help='输入 EPUB 文件')
    parser.add_argument('output', type=Path, help='输出破损 EPUB 文件')
    args = parser.parse_args()

    if not args.input.exists():
        print(f"❌ 输入文件不存在: {args.input}")
        exit(1)

    corrupt_zip_crc(args.input, args.output)
