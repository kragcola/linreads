#!/bin/bash
# 准备多应用极端测试环境

set -e

echo "📦 准备测试环境..."

# 1. 启动模拟器
echo "1️⃣  启动模拟器..."
emulator -avd readflow_test -no-snapshot-load &
EMULATOR_PID=$!

# 等待启动完成
adb wait-for-device
sleep 5
echo "✅ 模拟器已启动 (PID: $EMULATOR_PID)"

# 2. 推送测试文件
echo "2️⃣  推送测试文件..."
adb shell mkdir -p /sdcard/Download/ExtremTest
adb push test-assets/extreme-epubs/* /sdcard/Download/ExtremTest/
echo "✅ 测试文件已推送"

# 3. 构建LinReads
echo "3️⃣  构建LinReads..."
cd android
./gradlew -Preadflow.phase=2 assembleDebug
cd ..
echo "✅ LinReads构建完成"

# 4. 创建结果目录
echo "4️⃣  创建结果目录..."
mkdir -p docs/testing/evidence/screenshots
mkdir -p docs/testing/evidence/traces
mkdir -p docs/testing/evidence/logs
mkdir -p docs/testing/evidence/comparison
echo "✅ 结果目录已创建"

echo "🎉 环境准备完成！"
echo "模拟器PID: $EMULATOR_PID"
echo "LinReads APK: android/app/build/outputs/apk/phase2/debug/app-phase2-debug.apk"
