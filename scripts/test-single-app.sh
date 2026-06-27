#!/bin/bash
# 单应用极端测试脚本
# 用法: ./test-single-app.sh <app-name> <package> <apk-path>

APP_NAME=$1
PACKAGE=$2
APK_PATH=$3

if [ -z "$APP_NAME" ] || [ -z "$PACKAGE" ] || [ -z "$APK_PATH" ]; then
    echo "用法: $0 <app-name> <package> <apk-path>"
    echo "示例: $0 linreads dev.readflow app.apk"
    exit 1
fi

echo "📱 测试应用: $APP_NAME ($PACKAGE)"
echo "APK: $APK_PATH"
echo ""

# 检查设备
adb devices | grep device || { echo "❌ 无设备连接"; exit 1; }

# 安装应用
echo "1️⃣  安装应用..."
adb install -r "$APK_PATH"
sleep 2

# S1: 冷启动
echo "2️⃣  S1: 冷启动测试..."
adb shell pm clear "$PACKAGE"
START_TIME=$(date +%s%N)
adb shell am start -n "$PACKAGE"/.MainActivity -W > /dev/null 2>&1
END_TIME=$(date +%s%N)
STARTUP_TIME=$(( ($END_TIME - $START_TIME) / 1000000 ))
echo "   启动时间: ${STARTUP_TIME}ms"

sleep 3
adb shell screencap -p /sdcard/s1-$APP_NAME.png
adb pull /sdcard/s1-$APP_NAME.png docs/testing/evidence/screenshots/ > /dev/null 2>&1
echo "   截图: s1-$APP_NAME.png"

# S2: 标准EPUB
echo "3️⃣  S2: 标准EPUB测试..."
START_TIME=$(date +%s%N)
adb shell am start -a android.intent.action.VIEW \
  -d file:///sdcard/Download/ExtremTest/tiny-3para.epub \
  -t application/epub+zip > /dev/null 2>&1
END_TIME=$(date +%s%N)
OPEN_TIME=$(( ($END_TIME - $START_TIME) / 1000000 ))
echo "   打开时间: ${OPEN_TIME}ms"

sleep 3
adb shell screencap -p /sdcard/s2-$APP_NAME.png
adb pull /sdcard/s2-$APP_NAME.png docs/testing/evidence/screenshots/ > /dev/null 2>&1
echo "   截图: s2-$APP_NAME.png"

MEM=$(adb shell dumpsys meminfo "$PACKAGE" | grep "TOTAL" | awk '{print $2}')
echo "   内存占用: ${MEM}KB"

# S8: 排版质量
echo "4️⃣  S8: 排版质量截图..."
sleep 2
adb shell screencap -p /sdcard/s8-$APP_NAME.png
adb pull /sdcard/s8-$APP_NAME.png docs/testing/evidence/screenshots/ > /dev/null 2>&1
echo "   截图: s8-$APP_NAME.png"

echo ""
echo "✅ $APP_NAME 测试完成！"
echo "📊 结果保存在: docs/testing/evidence/screenshots/"
echo ""
