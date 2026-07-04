# EPUB 进书/滚动转分页 手感根因诊断 — reveal 应「等信号」而非「等定时」

> 日期：2026-07-04
> 范围：Android `:render:epub` — 进入已读书籍的定位闪现、SCROLL→PAGED 转换的可见别扭
> 状态：**仅诊断，未改产品代码**。本文供评审后再决定实现范围。
> 关联：`epub-paged-scroll-curl-investigation-2026-07-01.md`（更早的分页/临时滚动/curl 根因）、
> `moonreader-handfeel-borrowing-backlog-2026-07-02.md`

---

## 用户诉求

1. **问题1**：进入已经阅读过的书籍时，会瞬间显示「快速滚动到对应位置」的过程。
2. **问题2**：面对复杂场景（图文混排等）时，滚动转分页仍然很别扭，让人看得出来这是从滚动转成的分页。

目标：让人**看不出来**这是滚动转的分页，进书直接就在存档页，没有可见的滚动/重排/换版过程。

---

## 结论先行（一句话根因）

**当前 reveal（把内容从隐藏淡入可见）是「等一个固定的时间」触发的，但它本质上必须是「等布局真正稳定这个信号」触发的。**

- `alpha` 门只推迟「什么时候让用户看见」，**从不拦截 `scrollTo`**——位置跳转永远立即执行。
- 于是「什么时候可以安全 reveal」被实现成了一个**猜的定时器**（`INITIAL_REVEAL_SETTLE_MS = 80ms`）。
- 猜早了：异步图片解码后 `layout.height` 变化 → `reflowRunnable` 用新的 `scrollTo` 重新锚定，而这次滚动发生在 reveal *之后* → 用户看见内容滚动。这就是**问题1**。
- 转分页时用「冻结快照盖布 + 240ms 后一步硬撤」代替真正的等稳定 + 交叉淡出；底下分页帧若还没稳、或吸附到与快照不同的行，撤布瞬间露馅。这就是**问题2**。

过去针对这两个问题的多轮提交，方向都是**再加一层盖布**（冻结 viewport 快照、alpha 门、抑制交叉淡出、reflow 时重新藏）。每一层都是给一个「猜时间」的竞态盖更大的毯子——而毯子赢不了，因为那个 settle 时间本质上不可预知（取决于异步解码何时完成）。

这正是 `epub-paged-scroll-curl-investigation-2026-07-01.md` 明确警告过的：
> **"Do not fix this by adding another snapshot fallback."**

---

## 一、当前实现的精确控制流（代码级）

文件：`android/render/epub/src/main/kotlin/dev/readflow/render/epub/EpubFlowView.kt`（1719 行）
以及 `EpubReflowEngine.kt`、`EpubFlowImageLoader.kt`。行号为当前版本。

### 1.1 进书（冷启动）

```
EpubReflowEngine.createFlowView (:418)
  → loadFlowChapter (:506)
      → 计算 restoreOffset = flow.offsetForParagraph(...) (:566)
      → view.setChapter(flow, spannable, ..., restoreOffset) (:567)
      → view.textView.post { AsyncDrawableScheduler.schedule(view.textView) } (:570-572)  ← 图片解码在这之后才排期

EpubFlowView.setChapter (:547-577)
  awaitingReveal = true (:569)
  container.alpha = 0f (:573)                    ← 藏
  pendingRestoreOffset = restoreOffset (:566)
  textView.text = spannable (:574)
  textView.post { settleInitialPosition() } (:576)

settleInitialPosition (:585-592)
  repaginate(reposition = false) (:587)
  goToOffset(pendingRestoreOffset ?: 0, forceReport = true) (:588)   ← 瞬间 scrollTo，FLOOR 锚
  if (height > 0) scheduleInitialReveal() (:590)

onSizeChanged (:503-526)  ← 冷启动真实高度到达时
  if (awaitingReveal) { repaginate; goToOffset(pendingRestoreOffset); scheduleInitialReveal() }

scheduleInitialReveal (:605-609)
  postDelayed(initialRevealRunnable, INITIAL_REVEAL_SETTLE_MS=80ms)  ← 定时猜

revealContent (:611-629)
  awaitingReveal = false (:615)
  container.animate().alpha(1f).setDuration(120ms) (:620-622)        ← 淡入
```

### 1.2 异步图片解码后的重排（问题1 的直接元凶）

```
图片解码完成 (EpubFlowImageLoader :111-114) → drawable.result = d → TextView 重新布局 → layout.height 变
  → 布局监听 (:339-348) 检测 layout.height != paginatedLayoutHeight
  → 若 awaitingReveal：removeCallbacks(initialRevealRunnable) (:345)
  → postDelayed(reflowRunnable, 80ms) (:347)  合并解码风暴

reflowRunnable (:371-398)
  if (layout.height == paginatedLayoutHeight) return   (:374)  自己那次重排忽略
  if (turnInFlight) 延后重排 (:375-378)
  if (initialRevealActive()) { awaitingReveal = true; alpha = 0f; resetConversionSnapshotFade() } (:379-385)  ← 重新藏
  anchorOffset = pendingRestoreOffset ?: topLayoutOffset() (:386-390)
  recycleCachedTextures(); repaginate(reposition=false) (:391-392)
  goToOffset(anchorOffset, forceReport=true) (:393)      ← 又一次瞬间 scrollTo
  if (awaitingReveal) { positionConversionSnapshot(); scheduleInitialReveal() } (:395-396)
```

**问题1 的竞态窗口**：`revealContent` 在 80ms 时把 `awaitingReveal` 置 `false`、开始淡入；如果某张图片在这之后才解码完（大图/慢盘/首次冷解码常见），`reflowRunnable` 才触发 → 它试图重新藏（`initialRevealActive()` 检查 `container.alpha < 1f`），但如果淡入已经完成（alpha 已到 1），或正处于淡入动画中途，用户就会看到 `goToOffset` 的那次 `scrollTo` 把内容从"图片没撑开时的位置"移动到"图片撑开后的位置"——即**可见的快速滚动**。

### 1.3 SCROLL→PAGED 转换（问题2）

```
EpubReflowEngine.setMode (:1508-1558)  flow 路径
  offset = view.topLayoutOffset()  (:1519)   ← 用实时可见锚点（好）
  view.setModeAnchored(PAGED, offset) (:1524)

EpubFlowView.setModeAnchored (:74-92)
  hidePagedConversion = (modeValue==SCROLL && value==PAGED && flow!=null)   (:75)
  conversionSnapshot = snapshotViewport()  (:76)   ← 冻结当前 SCROLL 帧
  awaitingReveal = true; container.alpha = 0f (:82-83)                       ← 藏
  applyMode(value, reposition=false)  (:85)  recycleCachedTextures + repaginate
  goToOffset(layoutOffset, pagedAnchor = NEAREST, forceReport = true)  (:86) ← NEAREST 吸附
  showConversionSnapshot(conversionSnapshot)  (:88)  盖布（overlay，alpha=255）
  if (textView.layout != null) pendingRestoreOffset = topLayoutOffset() (:89)
  scheduleInitialReveal()  (:90)

revealContent (:611-629)  盖布特例
  if (conversionSnapshotDrawable != null)
      postDelayed(conversionSnapshotClearRunnable, REVEAL_FADE_MS*2 = 240ms) (:616-618)  ← 定时一步硬撤
  container.animate().alpha(1f).setDuration(120ms)   ← 底下 live 淡入
```

**问题2 的两个具体缺陷**：
- **盖布不是交叉淡出，是定时硬撤**：`ViewportSnapshotDrawable.alphaValue` 全程 255（`:1228` 只会把它设回 255，从来没有往下动画过），240ms 到点一步移除。若底下 live 分页帧此刻还没稳（还在解码/重排），撤布瞬间就露出"版面正在变"。
- **锚点规则不一致**：进书 `settleInitialPosition` 用 `FLOOR`（`:588`，`paged.indexOfLast { it.topPx <= y }`），而转分页 `setModeAnchored` 用 `NEAREST`（`:86`）。同一个字符 offset 在两条路径可能落到不同的页 → 冻结的 SCROLL 快照顶行 和 撤布后 live 分页顶行 对不齐，看起来"跳了一下"。

---

## 二、静读天下（MoonReader）真正的做法

反编译源：`moonreader-decompiled/sources/com/flyersoft/moonreaderp/ActivityTxt.java`、
`moonreader-decompiled/sources/com/flyersoft/staticlayout/MRTextView.java`。

### 2.1 核心：绘制真的被关掉，reveal 等信号不等定时

`MRTextView.onDraw` (MRTextView.java:239) 在 `disableDraw` 时**直接 early-return**，不画任何东西。activity 用两个开关控制：
- `disableTxtViewDraw()` (3501) — 关
- `enableTxtViewDraw()` (5773) — 开 + `postInvalidate()`

**关键：reveal 是等"布局真的准备好"这个信号，不是等固定时间。**
`getAndroid22Handler().handleMessage` (16168-16205)：

```java
if (txtView.getLayout() == null) {          // 布局还没测量
    fixBrokenTextView(true);
    if (!isPaused) handler.postDelayed(this, 200L);   // 重投，轮询等待
    return;
}
if (txtView.layoutState == 1) {             // 异步 SoftHyphen 排版还在跑
    getAndroid22Handler().sendEmptyMessageDelayed(0, 100L);  // 等 100ms 再查
    return;
}
...
int lineTop = getLineTopForPageTurn(txtView.getLayout().getLineForOffset((int) A.lastPosition));
txtScrollTo(lineTop);       // 先滚（此时绘制仍关着）
enableTxtViewDraw();        // 再开绘制 —— 用户看见的第一帧已经在目标位置
```

也就是：**先轮询等布局稳定 → 再 scrollTo → 再开绘制**。用户看见的第一帧永远在目标位置，绝无"先顶部后跳转"。

### 2.2 800ms 兜底重锚（应对异步排版晚到）

`showEBookByPosition` 的每个出口都 `postDelayed(..., 800L)` 一个重检 runnable（AnonymousClass63）：

```java
if (layout.getLineStart(layout.getLineForVertical(scrollY)) == A.lastPosition) return;  // 没漂移，OK
goToEBookLastPosition();   // 漂移了就重滚
```

即使异步布局晚到导致落位偏了，800ms 后静默重滚回去。

### 2.3 设置变更（字号/行距）的原地重排

`reloadBook()` (11534) → `handler.post(this)` **重跑同一套 open 状态机**（show message → showEBookByPosition → getAndroid22Handler → 先滚后现）。所以原地改字号/行距会用**同一套「先滚后现」机器**落回同一行，没有可见重排。position 靠 `saveLastPostion(true)` (19207) 先按当前顶行存成**字符 offset**，重排后用 `getLineForOffset` 在**新布局**上重新求像素目标——按字符锚，不按像素锚，所以对字号/行距变化免疫。

盖布（`scrollCache` + `txtCache`）停在同一个 scrollY，`HIDE_TXTCACHE` (7650) 只在**安全信号**（`flippingAnimationTime==0 && lastTouchAction==1`）时才撤，1500ms 定时仅作**兜底**。

### 2.4 结构性变更走完整 restart

字号/行距是原地重排；但对齐+斜体、翻页动画模式、CJK 字体开关、方向/折叠屏这类结构性变更，MoonReader 直接 `restartReaderIntent()` (19062)：`saveLastPostion(true)` → `finish()` → `overridePendingTransition(0,0)`（**无转场动画**）→ 重启 activity 冷开到存档行。因为无转场 + 冷开走「先滚后现」，看起来完全无缝。

---

## 三、对照表：LinReads 现状 vs MoonReader

| 维度 | LinReads 现状 | MoonReader | 差距 |
|------|--------------|-----------|------|
| reveal 触发 | 固定 `postDelayed(80ms)` | 轮询 `getLayout()!=null && layoutState!=1` **信号** | **核心差距** |
| 绘制抑制 | `container.alpha=0`（不拦 scrollTo） | `MRTextView.disableDraw` 真停绘制 | 等价即可（alpha 够用，只要 reveal 等信号） |
| 定位 | 瞬间 `scrollTo`（好） | 瞬间 `scrollTo`（好） | 一致 |
| 锚点 | 字符 offset（好），但开书 FLOOR / 转分页 NEAREST 不一致 | 字符 offset，统一 | **锚点规则要统一** |
| 异步晚到 | reflowRunnable 重排后可能已 reveal → 露馅 | 800ms 兜底重锚 + 信号 reveal | **缺兜底 + 缺信号门** |
| 转换盖布 | 240ms 定时一步硬撤，alpha 全程 255 | 安全信号撤 + 1500ms 兜底，可交叉淡出 | **撤布要等信号 + 交叉淡出** |
| 待解码信号 | `EpubFlowImageLoader.inFlight` map 已有，但 view 拿不到 | `layoutState` 可查 | **要把信号暴露给 view** |

---

## 四、建议修复方案（评审后再实现）

方向：**把「等定时」换成「等信号」，删掉猜测，而不是再加盖布。**

### 4.1 reveal 改为「布局稳定才触发」（治问题1 的根本）

把 `scheduleInitialReveal()` 的固定 `postDelayed(80ms)` 换成一个稳定门 `tryRevealWhenStable()`：

```
可以 reveal 当且仅当：
  layout != null
  && height > 0
  && layout.height == paginatedLayoutHeight       // 分页几何和当前布局一致
  && 没有待解码图片（见 4.4 的信号）
否则：不 reveal。
```

不稳就不 reveal。已有的布局监听（`:339-348`）、`reflowRunnable`（`:371-398`）、图片解码回调都会**重新调用这个门**——于是 reveal 精确地在**最后一次 reflow 落定**那一刻发生，而不是猜的 80ms。

### 4.2 加 800ms 兜底（照抄 MoonReader）

信号门必须配一个**最大等待兜底**（如 800ms），防止某张图永远解码不完导致内容永远藏着。兜底触发时：无论稳不稳，用当前 `pendingRestoreOffset` 再 `goToOffset` 一次然后强制 reveal。这也覆盖了「解码晚到 800ms 后的静默重锚」。

### 4.3 统一锚点规则

进书 `settleInitialPosition`（FLOOR）和转分页 `setModeAnchored`（NEAREST）用**同一个** paged 锚点规则。倾向统一为 `NEAREST`（视觉上最接近当前顶行的页），使冻结快照顶行与 reveal 后顶行对齐。需要跑现有 `EpubFlowViewTest` 里 FLOOR 相关断言，确认书签/目录跳转语义不被破坏（那些路径可能依赖 FLOOR 保证目标内容在页内）。

### 4.4 把「待解码」信号暴露给 view

`EpubFlowImageLoader` 已经维护 `inFlight`（`:80`，AsyncDrawable→Future 的 `WeakHashMap`）。补一个只读查询（如 `hasPendingDecodes(): Boolean` 或 `inFlightCount`），让 `EpubFlowView` 的稳定门（4.1）能查询。这是唯一需要跨类补的小口子。
> 注意 render:api 接口签名不要动（见 `render-api-default-method-stale-build`），这个信号走 loader 自己的公开方法即可，不碰 ReaderEngine 接口。

### 4.5 转换盖布改为「信号撤 + 交叉淡出」（治问题2）

`ViewportSnapshotDrawable.alphaValue` + `setAlpha` 本来就在（`:1684/:1706`），只是从没被动画过。改法：
- 底下 live 分页帧走 4.1 的稳定门确认稳定后，才开始撤盖布。
- 撤盖布用 `alphaValue` 从 255 动画到 0（交叉淡出），而不是 240ms 定时一步移除。
- 保留一个 1500ms 兜底硬撤（照抄 MoonReader 的 `HIDE_TXTCACHE`），防卡死。

### 4.6 不做的事（明确边界）

- **不**再加任何新的快照盖布层。
- **不**动 render:api 接口签名（AbstractMethodError 风险）。
- **不**改 `Locator.Section` 字段语义、选择/标注类型。
- **不**碰 SLIDE/GL curl 翻页本身的手感（那是另一条线，见 backlog）。
- 定时器只作**兜底**，绝不作主触发。

---

## 五、验证计划（实现阶段用）

单测（JVM/Robolectric，`EpubFlowViewTest`）：
1. `awaitingReveal` 期间，图片解码晚到触发 reflow → reveal **不得**在稳定前发生（RED 现状：会）。
2. 稳定门：`layout.height != paginatedLayoutHeight` 时 `tryRevealWhenStable` 不 reveal；相等且无待解码时才 reveal。
3. 800ms 兜底：模拟永久待解码 → 兜底到点仍 reveal，且落在 `pendingRestoreOffset`。
4. 锚点统一：同一 offset 经 `settleInitialPosition` 与 `setModeAnchored` 落到同一页。
5. 转换盖布交叉淡出：`conversionSnapshotDrawable.alphaValue` 会从 255 被动画降到 0，而非只在 240ms 后被移除。

真机（平板 OTA，物理手感 gate）— 用 `moonreader_handfeel_capture.py`：
1. 图文混排大书，存档在中段带整页插图的位置 → 关书 → 重开：**不得**看见滚动/图片弹入。
2. 复杂 SCROLL 滚到图文混排中段 → 切分页：**不得**看出版面在变。
3. 对比 MoonReader 同源语料同一场景。

> 遵 `never-fabricate-tool-outputs`：任何"验证通过"必须贴真实命令输出，AVD 证据明确标注"非物理手感"。

---

## 六、给 codex 的关键提醒

之前 12 次提交都在同一个方向（加盖布/加 alpha 门/抑制淡出/reflow 重藏）反复打转，始终"仍是 JVM/AVD 证据，不是真机物理手感"——因为它们都在给一个**猜时间的竞态**盖更大的毯子。本文的方案是**删掉那个猜测**（reveal 等信号），这才是收敛的方向。如果实现时又想"再加一层保护性快照/alpha 门"，那就是回到老路——停下来，先确认是不是稳定门本身没覆盖某个 reflow 入口。
