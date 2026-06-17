---
name: readflow-dev
description: >
  Readflow 三端阅读器（Android · HarmonyOS · Web）开发专用 skill。
  ✅ 适用：阅读功能、UI/UX、书库管理、Calibre 集成、格式渲染（EPUB/PDF）、跨平台一致性、构建发布。
  ❌ 不适用：与阅读器无关的通用 Python/服务端开发（走 omubot 项目的规则）。
  显式调用：/readflow-dev
---

# Readflow Dev

## Step 0：动手前先核实

遇到以下任意一条，**先搜索/查文档，不靠记忆断言**：

- 用到 `@ohos.*` API → 确认当前 `compileSdkVersion`（`harmony/build-profile.json5`）是否支持
- 用到 epubjs / Ktor 某版本特性 → 看 `web/package.json` / `android/app/build.gradle.kts` 的锁定版本
- 提到"静读天下怎么做 X" → 先 grep `moonreader-decompiled/` 再说，不要凭印象

禁止的短语（没有先查就不能说）：
*"我记得 HarmonyOS 5 支持…" / "epubjs 应该有这个 API" / "静读天下大概是这样实现的"*

---

## 四条行为规则（Karpathy-derived）

### R1 先想再动
实现前显式陈述假设。有歧义 → 先问，不要默默选一种。
有更简单的路 → 说出来，哪怕这条路和用户描述的不同。

### R2 极简
**写能解决问题的最少代码**。
- 不加没要求的功能（"顺便加个…"）
- 单次使用的代码不做抽象
- 写了 150 行发现 50 行能解决 → 重写

*自测：高级工程师看到这段代码会说"过度设计了"吗？*

### R3 外科手术
**只改必须改的，不动其他**。
- 不"顺便"整理相邻代码或注释
- 发现无关死代码 → 提一句，不要删
- 每一行改动都能直接追溯到用户的要求

### R4 目标驱动循环
把任务转化为可验证的目标再执行：

```
"加翻页动画" → "Web 端 CSS transition 左右滑动，在 Chrome/Safari 通过；
               Android 端 ViewPager2 动画，adb 截图对比静读天下效果"
```

多步任务先列计划：`步骤 → 验证方法`，自己跑完验证再声明完成。

---

## 红旗表（出现这些想法 = 停下来）

| 这个念头 | 真相 |
|----------|------|
| "这只是小改动，不用查" | 小改动破坏最多。先 grep 同模式位点。 |
| "我先跑起来再说" | 跑起来 ≠ 正确。先定验收标准。 |
| "另外两端以后再说" | 三端状态必须在 commit 里声明，不是以后的事。 |
| "这个 API 我记得用过" | HarmonyOS API 每个 SDK 版本都可能变。查文档。 |
| "静读天下肯定也是这么做的" | 去 grep，不要猜。 |
| "配置先写死，后面再改" | Calibre baseUrl/认证**永远不得硬编码**，见 C2。 |
| "测试留给用户反馈" | Web 端可在30秒内验证，没有理由跳过。 |

---

## 阅读器 UX 领域知识

这是 skill 的核心。阅读体验好坏由以下参数决定：

### 排版（最高优先级）
- **行高**：正文 `line-height: 1.6~1.8`；低于 1.5 = 密集疲劳
- **行宽**：每行 **45~75 字符**（中文约 28~40 字）；过宽眼睛难以换行
- **字号**：正文 ≥ 16px（Web）/ 16sp（Android）/ 16fp（HarmonyOS）；夜间可放大 1~2px
- **字体选择**：长文阅读用衬线体（Georgia / 方正书宋 / 思源宋体），代码块用等宽体
- **段间距**：`margin-bottom: 1em`，比行内间距大，给视觉停顿

### 对比度与色彩
- **日间模式**：背景 `#FAFAF8`（暖白）而非纯白 `#FFFFFF`，减少刺眼
- **夜间模式**：背景 `#1A1A1A`，文字 `#E8E6E1`（非纯白，防光晕）
- **护眼模式**：背景 `#F5F0E8`（淡黄），参考 Kindle Warm 预设
- WCAG AA 对比度 ≥ 4.5:1（正文）/ ≥ 3:1（大标题）

### 交互
- **翻页方式**：仿真翻页（卷曲）/ 平移滑动 / 上下滚动（竖版文本）— 三种都要支持
- **点击区域**：左 1/3 上翻，右 2/3 下翻（静读天下默认）；支持用户自定义
- **手势**：捏合缩放调字号；长按划选文字；双击全屏切换
- **进度条**：底部细线 + 百分比，不遮内容；拖动实时预览页码

### 查阅静读天下参考时优先查这些
```bash
# 阅读器核心 Activity
grep -r "class.*Activity" moonreader-decompiled/sources/com/ --include="*.java" | grep -i "read\|book\|viewer"

# 翻页/手势相关
find moonreader-decompiled/sources -name "*.java" | xargs grep -l "PageTurn\|GestureDetector\|Swipe" 2>/dev/null

# 字体/排版设置
grep -r "lineSpacing\|textSize\|fontFamily" moonreader-unpacked/res/layout/ 2>/dev/null | head -20

# 夜间模式色值
grep -r "night\|dark" moonreader-unpacked/res/values/ --include="*.xml" | head -20
```

---

## UI 功能 Workflow（有 UI 变更时走此流程）

```
Step 1  明确范围 → 哪个平台？影响哪些屏幕？
Step 2  查 moonreader 参考 → grep 找对应实现，标注出处
  ⛔ Checkpoint: 确认参考来源，不靠记忆
Step 3  Web 端先原型 → npm run dev 验证布局/交互
  ⛔ Checkpoint: 截图/描述给用户确认方向
Step 4  移植到 Android / HarmonyOS
Step 5  三端验证 → 见验证矩阵
Step 6  commit 含三端状态声明
```

非 UI 变更（纯逻辑/API/数据层）跳过 Step 2-3，直接 Step 4-5。

---

## 平台速查

| 端 | 目录 | 入口 | 构建验证 |
|----|------|------|----------|
| Android | `android/` | `MainActivity.kt` | `./gradlew lint test` |
| HarmonyOS | `harmony/` | `pages/Index.ets` | DevEco → Build HAP |
| Web | `web/` | `src/main.tsx` | `npm run build && tsc` |
| 共享契约 | `shared/api/calibre-contract.ts` | — | `tsc --noEmit` |

**Android 明文 HTTP**：Android 9+ 默认禁止，局域网 Calibre 需在 `res/xml/network_security_config.xml` 加 `<domain includeSubdomains="true">192.168.x.x</domain>`。

---

## Calibre 集成纪律（C2 展开）

**配置永远来自用户设置，以下是绝对禁止：**
```kotlin
// ❌
val baseUrl = "http://192.168.1.1:8080"
// ✅
val baseUrl = settings.calibreBaseUrl ?: return showSetupGuide()
```

API 速查：
```
GET /ajax/search?query=&num=100     → {total_num, book_ids[]}
GET /ajax/books?ids=1,2,3           → {id: BookMeta} map
GET /get/<FMT>/<id>/calibre-library → 文件流
GET /get/cover/<id>/calibre-library → 封面图
```

格式优先级：`EPUB > AZW3 > MOBI > PDF`。无可用格式 → 明确提示，不静默失败。

---

## 三端同步规则（C1）

任何**用户可见功能**的 commit message 必须含：

```
Platform: Android ✅ | HarmonyOS 待实现(#12) | Web N/A(无浏览器端需求)
```

不写 = 未完成。

---

## 交付前检查清单

声明"完成"前逐项过：

- [ ] Step 0 跑过：用到的 API/版本已从源码/文档确认，非记忆
- [ ] R2 自测通过：没有未被要求的功能
- [ ] R3 自测通过：`git diff` 里没有无关改动
- [ ] 三端状态已在 commit 中声明
- [ ] Calibre baseUrl 未硬编码
- [ ] Web 端 `npm run build` 无错误
- [ ] 阅读器 UI 改动：line-height ≥ 1.6，正文 ≥ 16px，对比度 ≥ 4.5:1
- [ ] 夜间模式：背景非纯黑，文字非纯白

---

## 验证矩阵

| 层级 | 最快路径 | 证据形式 |
|------|----------|----------|
| 逻辑正确 | Web `npm test` / Android `./gradlew test` | 测试输出 |
| UI 渲染 | Web `npm run dev` + 浏览器截图 | 截图描述 |
| 真机效果 | `adb install` + logcat | 日志/截图 |
| Calibre 连通 | curl 打本地 Calibre server | HTTP 200 |

---

## 交付收尾格式

```
## 完成
- 改动：file:line
- 三端：Android ✅ | HarmonyOS 待 | Web N/A

## 证据
<命令输出或截图描述>

## 遗留
<未覆盖的 edge case 或待确认项，没有则写"无">
```
