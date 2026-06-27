# 多应用极端阅读测试方案 - 模拟器实测版

> **测试日期**: 2026-06-27  
> **测试目标**: 在模拟器中对比测试 LinReads、静读天下、Librera、FBReader、ReadEra 的极端阅读场景表现  
> **测试方法**: 统一测试文件 + 统一操作流程 + 客观数据记录  
> **状态**: 准备执行

---

## 一、测试环境准备

### 1.1 模拟器配置

```bash
# AVD 配置
Name: readflow_test
System: Android 16 (API 36)
Device: Pixel 6
Resolution: 1080x2400 (420dpi)
RAM: 4GB
Storage: 8GB
```

### 1.2 测试应用清单

| 应用 | 版本 | 来源 | 安装状态 |
|------|------|------|---------|
| **LinReads** | Dev #126 | 本地构建 | ⏳ 待安装 |
| **静读天下** | v9.7 | moonreader-pro.apk | ⏳ 待安装 |
| **Librera** | 最新 | Play Store / APK | ⏳ 待下载 |
| **FBReader** | 最新 | Play Store / APK | ⏳ 待下载 |
| **ReadEra** | 最新 | Play Store / APK | ⏳ 待下载 |

### 1.3 测试文件准备

使用已生成的极端测试语料：

```bash
test-assets/extreme-epubs/
├── deep-nested-97.epub       # 97层DOM嵌套
├── deep-nested-100.epub      # 100层DOM嵌套  
├── no-toc.epub               # 缺少目录
├── single-para-50k.epub      # 50000字单段落
├── tiny-3para.epub           # 最小EPUB (3段)
├── image-heavy-20.epub       # 20张内联图片
└── standard-test.epub        # 标准测试书 (傲慢与偏见)
```

**新增测试文件** (需生成):
- `mega-novel-500ch.epub` - 50MB, 500章节
- `corrupted-50percent.epub` - 破损ZIP
- `test-pdf-100pages.pdf` - 100页标准PDF
- `huge-txt-50mb.txt` - 50MB纯文本

---

## 二、测试场景设计

### 2.1 S1: 冷启动速度

**目标**: 测试应用冷启动到可操作的时间

**步骤**:
```bash
# 清除应用数据
adb shell pm clear <package>

# 记录启动时间
time adb shell am start -n <package>/<activity> -W

# 等待完全加载
sleep 2

# 截图验证
adb shell screencap -p /sdcard/s1-<app>-coldstart.png
```

**记录指标**:
- 启动时间 (秒)
- 内存占用 (MB)
- 是否有引导页面

### 2.2 S2: 标准EPUB打开

**目标**: 测试打开标准EPUB的速度和初始渲染

**测试文件**: `standard-test.epub` (傲慢与偏见, ~500KB)

**步骤**:
```bash
# 推送文件
adb push test-assets/standard-test.epub /sdcard/Download/

# 通过文件管理器打开
adb shell am start -a android.intent.action.VIEW \
  -d file:///sdcard/Download/standard-test.epub \
  -t application/epub+zip

# 记录打开时间
time <上述命令>

# 截图首页渲染
adb shell screencap -p /sdcard/s2-<app>-epub-open.png

# 检查内存
adb shell dumpsys meminfo <package> | grep TOTAL
```

**记录指标**:
- 打开时间 (秒)
- 首页渲染质量 (截图)
- 内存峰值 (MB)

### 2.3 S3: 极端嵌套HTML

**目标**: 测试深层嵌套HTML的解析能力

**测试文件**: `deep-nested-100.epub` (100层div)

**步骤**:
1. 打开文件
2. 检查是否崩溃
3. 检查渲染是否正确
4. 翻页是否卡顿

**预期**:
- LinReads: 触发96层截断，不崩溃
- 其他应用: 待观察

### 2.4 S4: 超长单段落

**目标**: 测试50000字无换行段落的渲染

**测试文件**: `single-para-50k.epub`

**检查项**:
- [ ] 是否崩溃
- [ ] 是否正确换行
- [ ] 滚动是否流畅
- [ ] 内存占用

### 2.5 S5: 大文件压力测试

**目标**: 测试50MB EPUB的打开速度和内存占用

**测试文件**: `mega-novel-500ch.epub` (待生成)

**步骤**:
```bash
# 生成大文件
python3 scripts/extreme-test-generators/gen_large_epub.py \
  --chapters 500 \
  --size 50MB \
  --output test-assets/extreme-epubs/mega-novel-500ch.epub

# 推送并打开
adb push test-assets/extreme-epubs/mega-novel-500ch.epub /sdcard/Download/

# 监控内存
adb shell "dumpsys meminfo <package> | grep TOTAL" &

# 打开文件并计时
time adb shell am start -a android.intent.action.VIEW \
  -d file:///sdcard/Download/mega-novel-500ch.epub
```

**目标指标**:
- 打开时间 < 5秒
- 内存峰值 < 500MB
- 无卡顿

### 2.6 S6: PDF渲染

**目标**: 对比PDF渲染质量和性能

**测试文件**: `test-pdf-100pages.pdf`

**步骤**:
1. 打开PDF
2. 翻页到第50页
3. 放大2倍
4. 截图对比渲染质量

**已知结果**:
- LinReads: PdfRenderer稳定
- 静读天下: AVD崩溃

### 2.7 S7: 翻页性能

**目标**: 测量翻页帧率和响应时间

**方法**:
```bash
# 使用systrace记录
python3 $ANDROID_HOME/platform-tools/systrace/systrace.py \
  -o s7-<app>-page-turn.html \
  -t 10 gfx view input

# 手动翻页10次
adb shell input swipe 800 500 200 500 100
# 重复10次

# 分析帧率
grep "Frame" s7-<app>-page-turn.html
```

**目标**: ≥30fps

### 2.8 S8: 排版质量对比

**目标**: 并排对比排版参数

**检查项**:
```bash
# 截图保存
adb shell screencap -p /sdcard/s8-<app>-typography.png
adb pull /sdcard/s8-<app>-typography.png docs/testing/evidence/

# 并排对比
# - 字号
# - 行高
# - 段间距
# - 边距
# - 对比度
```

**工具**:
- 对比度检测: https://webaim.org/resources/contrastchecker/
- 图像对比: ImageMagick

### 2.9 S9: 畸形文件容错

**目标**: 测试破损文件的处理

**测试文件**:
- `no-toc.epub` - 缺少目录
- `corrupted-50percent.epub` - 破损ZIP

**预期**:
- 优雅降级，不崩溃
- 显示友好错误提示

### 2.10 S10: 手势响应

**目标**: 测试手势延迟

**方法**:
```bash
# 录屏
adb shell screenrecord /sdcard/s10-<app>-gesture.mp4 &

# 执行手势
adb shell input swipe 800 500 200 500 100  # 翻页
sleep 1
adb shell input tap 200 500                 # 点击左侧
sleep 1
adb shell input tap 800 500                 # 点击右侧

# 分析录屏，测量响应时间
```

**目标**: 响应时间 <100ms

---

## 三、测试执行矩阵

### 3.1 测试覆盖表

| 场景 | LinReads | 静读天下 | Librera | FBReader | ReadEra |
|------|---------|---------|---------|----------|---------|
| S1: 冷启动 | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ |
| S2: 标准EPUB | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ |
| S3: 深层嵌套 | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ |
| S4: 超长段落 | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ |
| S5: 大文件 | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ |
| S6: PDF | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ |
| S7: 翻页性能 | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ |
| S8: 排版质量 | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ |
| S9: 容错 | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ |
| S10: 手势 | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ |

### 3.2 数据记录模板

```markdown
## 测试结果: <应用名>

### S1: 冷启动
- 启动时间: ___ 秒
- 内存占用: ___ MB
- 引导页面: 是/否
- 截图: s1-<app>-coldstart.png

### S2: 标准EPUB
- 打开时间: ___ 秒
- 内存峰值: ___ MB
- 渲染质量: 优/良/中/差
- 截图: s2-<app>-epub-open.png

### S3: 深层嵌套
- 是否崩溃: 是/否
- 渲染正确: 是/否
- 性能影响: 无/轻微/严重

### S4: 超长段落
- 是否崩溃: 是/否
- 换行正确: 是/否
- 滚动流畅: 是/否
- 内存占用: ___ MB

### S5: 大文件
- 打开时间: ___ 秒
- 内存峰值: ___ MB
- 是否卡顿: 是/否

### S6: PDF
- 打开成功: 是/否
- 渲染质量: 优/良/中/差
- 缩放流畅: 是/否

### S7: 翻页性能
- 平均帧率: ___ fps
- 最低帧率: ___ fps
- 是否掉帧: 是/否

### S8: 排版质量
- 字号: ___ sp
- 行高: ___ 倍
- 对比度: ___ :1
- 视觉舒适度: 优/良/中/差

### S9: 容错
- 畸形文件处理: 优雅/普通/崩溃
- 错误提示: 友好/简单/无

### S10: 手势
- 翻页响应: ___ ms
- 点击响应: ___ ms
- 手势流畅度: 优/良/中/差
```

---

## 四、执行脚本

### 4.1 环境准备脚本

```bash
#!/bin/bash
# prepare-test-env.sh

set -e

echo "📦 准备测试环境..."

# 1. 启动模拟器
echo "1️⃣  启动模拟器..."
emulator -avd readflow_test -no-snapshot-load &
EMULATOR_PID=$!

# 等待启动完成
adb wait-for-device
echo "✅ 模拟器已启动"

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
echo "✅ 结果目录已创建"

echo "🎉 环境准备完成！"
```

### 4.2 单应用测试脚本

```bash
#!/bin/bash
# test-single-app.sh <app-name> <package> <apk-path>

APP_NAME=$1
PACKAGE=$2
APK_PATH=$3

echo "📱 测试应用: $APP_NAME"

# 安装应用
echo "1️⃣  安装应用..."
adb install -r "$APK_PATH"

# S1: 冷启动
echo "2️⃣  S1: 冷启动测试..."
adb shell pm clear "$PACKAGE"
START_TIME=$(date +%s%N)
adb shell am start -n "$PACKAGE"/.MainActivity -W
END_TIME=$(date +%s%N)
STARTUP_TIME=$(( ($END_TIME - $START_TIME) / 1000000 ))
echo "   启动时间: ${STARTUP_TIME}ms"

sleep 2
adb shell screencap -p /sdcard/s1-$APP_NAME.png
adb pull /sdcard/s1-$APP_NAME.png docs/testing/evidence/screenshots/

# S2: 标准EPUB
echo "3️⃣  S2: 标准EPUB测试..."
START_TIME=$(date +%s%N)
adb shell am start -a android.intent.action.VIEW \
  -d file:///sdcard/Download/ExtremTest/standard-test.epub \
  -t application/epub+zip
END_TIME=$(date +%s%N)
OPEN_TIME=$(( ($END_TIME - $START_TIME) / 1000000 ))
echo "   打开时间: ${OPEN_TIME}ms"

sleep 3
adb shell screencap -p /sdcard/s2-$APP_NAME.png
adb pull /sdcard/s2-$APP_NAME.png docs/testing/evidence/screenshots/

MEM=$(adb shell dumpsys meminfo "$PACKAGE" | grep "TOTAL" | awk '{print $2}')
echo "   内存占用: ${MEM}KB"

# S7: 翻页性能
echo "4️⃣  S7: 翻页性能测试..."
python3 $ANDROID_HOME/platform-tools/systrace/systrace.py \
  -o docs/testing/evidence/traces/s7-$APP_NAME.html \
  -t 5 gfx view &

sleep 1
for i in {1..10}; do
  adb shell input swipe 800 500 200 500 100
  sleep 0.5
done

sleep 2

# S8: 排版质量
echo "5️⃣  S8: 排版质量截图..."
adb shell screencap -p /sdcard/s8-$APP_NAME.png
adb pull /sdcard/s8-$APP_NAME.png docs/testing/evidence/screenshots/

echo "✅ $APP_NAME 测试完成！"
echo ""
```

### 4.3 批量测试脚本

```bash
#!/bin/bash
# run-all-tests.sh

set -e

echo "🚀 开始批量测试..."

# 准备环境
bash scripts/prepare-test-env.sh

# 测试LinReads
bash scripts/test-single-app.sh \
  linreads \
  dev.readflow \
  android/app/build/outputs/apk/phase2/debug/app-phase2-debug.apk

# 测试静读天下
bash scripts/test-single-app.sh \
  moonreader \
  com.flyersoft.moonreaderp \
  moonreader-pro.apk

# 测试其他应用 (如果APK可用)
# bash scripts/test-single-app.sh librera ...
# bash scripts/test-single-app.sh fbreader ...
# bash scripts/test-single-app.sh readera ...

echo "🎉 所有测试完成！"
echo "📊 查看结果: docs/testing/evidence/"
```

---

## 五、预期结果

### 5.1 LinReads预期表现

| 场景 | 预期结果 | 置信度 |
|------|---------|--------|
| S1 | 启动 <2s, 内存 <150MB | 高 |
| S2 | 打开 <1s, 渲染正确 | 高 |
| S3 | 不崩溃, 触发截断 | 高 |
| S4 | 正确渲染, 流畅滚动 | 中 |
| S5 | 打开 <5s, 内存 <500MB | 中 |
| S6 | 成功渲染, 无崩溃 | 高 |
| S7 | ≥30fps | 中 |
| S8 | 18sp, 1.75行高 | 高 |
| S9 | 优雅降级 | 高 |
| S10 | 响应 <100ms | 中 |

### 5.2 静读天下预期表现

| 场景 | 预期结果 | 基于 |
|------|---------|------|
| S1 | 启动 <2s | AVD历史数据 |
| S6 | PDF崩溃 | AVD历史数据 |
| S8 | 默认1.2行高 | 代码分析 |

---

## 六、输出物

### 6.1 测试报告

**文件**: `docs/testing/multi-app-extreme-test-results.md`

**结构**:
1. 测试环境说明
2. 逐应用测试结果
3. 横向对比表格
4. 截图证据索引
5. 性能图表
6. 结论与建议

### 6.2 证据归档

```
docs/testing/evidence/
├── screenshots/
│   ├── s1-linreads-coldstart.png
│   ├── s2-linreads-epub-open.png
│   ├── s8-linreads-typography.png
│   ├── s1-moonreader-coldstart.png
│   └── ...
├── traces/
│   ├── s7-linreads-page-turn.html
│   ├── s7-moonreader-page-turn.html
│   └── ...
├── logs/
│   ├── linreads-meminfo.log
│   ├── moonreader-logcat.log
│   └── ...
└── comparison/
    ├── typography-side-by-side.png
    └── performance-chart.png
```

---

## 七、执行时间线

| 阶段 | 预计时间 | 任务 |
|------|---------|------|
| **Phase 1** | 1小时 | 环境准备、APK获取 |
| **Phase 2** | 2小时 | LinReads + 静读天下测试 |
| **Phase 3** | 2小时 | Librera/FBReader/ReadEra测试 |
| **Phase 4** | 1小时 | 数据整理、截图对比 |
| **Phase 5** | 2小时 | 撰写测试报告 |
| **总计** | **8小时** | 完整测试流程 |

---

**准备完成，等待执行指令！**
