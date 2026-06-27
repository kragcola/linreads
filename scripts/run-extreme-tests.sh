#!/bin/bash
# 极端情况离线阅读测试 - 一键执行脚本
set -e

echo "📦 极端情况离线阅读测试套件"
echo "================================"
echo ""

# 检查依赖
echo "🔍 检查依赖..."
command -v python3 >/dev/null 2>&1 || { echo "❌ 需要 Python 3"; exit 1; }
command -v java >/dev/null 2>&1 || { echo "❌ 需要 Java"; exit 1; }
echo "✅ 依赖检查通过"
echo ""

# 生成测试文件
echo "🎲 生成测试文件..."
GENERATORS_DIR="scripts/extreme-test-generators"
OUTPUT_DIR="test-assets/extreme-epubs"
mkdir -p "$OUTPUT_DIR"

python3 "$GENERATORS_DIR/gen_deep_nested_epub.py" --depth 97 --output "$OUTPUT_DIR/deep-nested-97.epub"
python3 "$GENERATORS_DIR/gen_deep_nested_epub.py" --depth 100 --output "$OUTPUT_DIR/deep-nested-100.epub"
python3 "$GENERATORS_DIR/gen_minimal_epub.py" --paragraphs 3 --output "$OUTPUT_DIR/tiny-3para.epub"
python3 "$GENERATORS_DIR/gen_malformed_epub.py" --missing toc,nav --output "$OUTPUT_DIR/no-toc.epub"
python3 "$GENERATORS_DIR/gen_long_paragraph_epub.py" --chars 50000 --output "$OUTPUT_DIR/single-para-50k.epub"
python3 "$GENERATORS_DIR/gen_image_heavy_epub.py" --images 50 --image-size 10 --output "$OUTPUT_DIR/image-heavy-50.epub"

echo "✅ 已生成 6 个测试文件"
ls -lh "$OUTPUT_DIR"
echo ""

# 复制到 Android 测试目录
echo "📋 复制到 Android 测试目录..."
ANDROID_TEST_DIR="android/render/epub/test-assets/extreme-epubs"
mkdir -p "$ANDROID_TEST_DIR"
cp -v "$OUTPUT_DIR"/* "$ANDROID_TEST_DIR/"
echo ""

# 运行 Android 单元测试
echo "🧪 运行 Android EPUB 极端测试..."
cd android
./gradlew :render:epub:test

echo ""
echo "📊 测试结果："
cat render/epub/build/test-results/testDebugUnitTest/TEST-dev.readflow.render.epub.EpubExtremeTest.xml | \
  grep -E "testsuite name" | \
  sed 's/.*tests="\([0-9]*\)".*failures="\([0-9]*\)".*errors="\([0-9]*\)".*/测试: \1  失败: \2  错误: \3/'

echo ""
echo "📄 测试报告："
REPORT_PATH=$(find render/epub/build/reports/tests -name "index.html" | head -1)
if [ -n "$REPORT_PATH" ]; then
  echo "  file://$(pwd)/$REPORT_PATH"
else
  echo "  ⚠️  未找到 HTML 报告"
fi

echo ""
echo "✅ 测试执行完成！"
echo ""
echo "📝 下一步："
echo "  1. 查看详细报告: open $REPORT_PATH"
echo "  2. 修复发现的缺陷"
echo "  3. 推送到真机测试"
