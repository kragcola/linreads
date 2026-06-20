# Android 手写笔可行性调研

> 调研日期：2026-06-17
> 目标：在 LinReads Android 端文档视图上叠加手写笔注记层
> 结论先行：**可行，推荐使用 androidx.ink（1.0.0 已正式发布）**

---

## 一、技术选型对比

| 方案 | 延迟 | 复杂度 | 笔压/倾斜 | 防掌误触 | 序列化 | 适合度 |
|------|------|--------|---------|---------|--------|--------|
| **A. androidx.ink** | 最低（前缓冲区渲染）| 低 | ✅ 内置 | ✅ 系统级 | ✅ 内置 | ★★★★★ |
| B. Canvas + Path（自实现）| 中（标准双缓冲）| 高 | 手动读 MotionEvent | 手动过滤 | 手动 | ★★★ |
| C. GLSurfaceView 手写 | 低（OpenGL）| 极高 | ✅ | 手动 | 手动 | ★★（成本过高）|

**选 A。** `androidx.ink` 1.0.0 于 2025-12-17 正式发布（非 alpha），Google Circle-to-Search 已在生产使用。

---

## 二、androidx.ink 模块结构

```
androidx.ink
  ├── ink-authoring          # InProgressStrokesView：实时渲染中的笔画（前缓冲区）
  ├── ink-strokes            # Stroke / StrokeInputBatch 核心数据类型
  ├── ink-brush              # Brush：画笔外观（粗细、颜色、纹理、荧光笔）
  ├── ink-geometry           # 几何运算（交集、覆盖 → 选中/擦除工具）
  ├── ink-rendering          # CanvasStrokeRenderer：渲染完成笔画到 View
  ├── ink-storage            # StrokeInputBatch 序列化（紧凑 proto，1.0.0-alpha03 引入）
  └── ink-nativeloader       # C++ 底层加载器（自动依赖，无需手动调用）
```

Gradle（当前最新稳定 + 最新 alpha）：
```kotlin
// 最新稳定
implementation("androidx.ink:ink-authoring:1.0.0")
implementation("androidx.ink:ink-strokes:1.0.0")
implementation("androidx.ink:ink-brush:1.0.0")
implementation("androidx.ink:ink-rendering:1.0.0")
implementation("androidx.ink:ink-storage:1.0.0")
// 1.1.0-alpha03（2026-05-19）已加入可编程画笔 API
```

---

## 三、低延迟渲染原理

### 标准路径 vs 前缓冲区路径

```
标准路径（~30–50ms @ 60Hz）：
  MotionEvent → GPU 写入 back buffer → SurfaceFlinger 等 vsync → 合成 → 显示
  
前缓冲区路径（~9ms @ 60Hz）：
  MotionEvent → 直接写入 front buffer（显示器正在扫描的缓冲区）→ 立即可见
```

SurfaceFlinger 合成等待约 16.6ms（1个 vsync）被完全跳过。`InProgressStrokesView` 封装了这整套逻辑，开发者无需接触 `GLFrontBufferedRenderer` / `CanvasFrontBufferedRenderer` 底层。

### MotionEventPredictor（运动预测）

`MotionEventPredictor` 基于近几帧输入预测下一帧笔的位置，提前绘制。两者叠加后感知延迟降至 <10ms。

**副作用**：快速停笔时有短暂"橡皮筋"反弹（预测点被实际 ACTION_UP 覆盖），这是业界已知权衡，GoodNotes / Notability 同样存在。

---

## 四、关键 API 能力清单

| 能力 | API | 备注 |
|------|-----|------|
| 笔 vs 手指区分 | `MotionEvent.getToolType(0) == TOOL_TYPE_STYLUS` | 系统事件级别区分 |
| 笔压（0.0–1.0）| `MotionEvent.getPressure()` | 硬件支持则有效，无支持返回 1.0 |
| 倾斜角 | `getAxisValue(AXIS_TILT)` | 弧度，0=垂直，π/2=水平 |
| 笔方向 | `getOrientation()` | 弧度，绕 Z 轴 |
| 防手掌误触 | Android 系统下发 `ACTION_CANCEL` | 无需应用自实现，监听取消事件即可 |
| 悬浮检测 | `ACTION_HOVER_*` | 可做 hover 预览 |
| 历史采样点 | `getHistoricalX/Y/Pressure()` | 60Hz 输入但 120Hz 渲染时补帧用 |

---

## 五、叠加架构设计

### 视图层次

```
FrameLayout (AnnotationContainer)
  ├── WebView / RecyclerView（文档渲染层）
  ├── CanvasView（已完成笔画，标准双缓冲 View）
  └── InProgressStrokesView（进行中笔画，前缓冲区，透明叠加）
```

### 输入路由策略

不做模式切换按钮，用工具类型自动路由：

```kotlin
override fun onTouchEvent(e: MotionEvent): Boolean {
    return when (e.getToolType(0)) {
        MotionEvent.TOOL_TYPE_STYLUS,
        MotionEvent.TOOL_TYPE_ERASER -> {
            inkLayer.handleStylusEvent(e)   // 笔 → 手写层
            true
        }
        else -> false  // 手指 → 继续传递给文档 WebView（滚动/点击）
    }
}
```

手指滚动文档、笔书写注记，**两者互不干扰，无需切换模式**。

---

## 六、存储方案

### 数据模型

```kotlin
@Entity(tableName = "annotations")
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val docId: String,         // SHA-256(文件绝对路径)前16字符
    val pageRef: String,       // EPUB: chapterId；PDF: "page:5"
    val strokeData: ByteArray, // ink-storage 序列化的 StrokeInputBatch
    val updatedAt: Long,       // 时间戳，同步用 LWW 键
)
```

`ink-storage` 的 `StrokeInputBatch` 序列化比传统 List<PointF> JSON 小约70%（官方数据），适合直接存 Room `ByteArray` 列。

### DAO

```kotlin
@Dao
interface AnnotationDao {
    @Query("SELECT * FROM annotations WHERE docId = :docId AND pageRef = :pageRef")
    suspend fun getPage(docId: String, pageRef: String): List<AnnotationEntity>

    @Upsert
    suspend fun save(a: AnnotationEntity)

    @Query("DELETE FROM annotations WHERE docId = :docId AND pageRef = :pageRef AND id = :id")
    suspend fun deleteStroke(docId: String, pageRef: String, id: Long)
}
```

---

## 七、已知限制和边界条件

| 限制 | 影响 | 处理方式 |
|------|------|---------|
| 前缓冲区不支持 alpha 混合 | 半透明荧光笔在进行中时有色差 | 提交（ACTION_UP）后再以正确混合模式重绘 |
| InProgressStrokesView.maskPath 闪烁 | 特定设备，1.1.0-alpha02 已修复 | 使用 1.1.0-alpha03 |
| 不支持 API < 26 | LinReads 已设 minSdk 26，无影响 | — |
| PDF 页面滚动时坐标系变化 | 笔画坐标需变换 | 存储相对于页面内坐标，渲染时加滚动偏移 |
| 鸿蒙端 | 此方案仅 Android | HarmonyOS 需独立方案（见单独文档）|

---

## 八、实现路径（分阶段）

### Phase 1：基础手写（2周）
- 引入 `ink-authoring` + `ink-strokes` + `ink-rendering`
- `InProgressStrokesView` 叠加在文档 WebView 上
- 笔/手指自动路由（见上方代码）
- Room 存储已完成笔画（`ink-storage` 序列化）
- 支持撤销/重做（维护 `strokeStack`）

### Phase 2：工具栏（1周）
- 画笔颜色 / 粗细选择（`Brush` API）
- 橡皮擦（`TOOL_TYPE_ERASER` + `ink-geometry` 交集检测）
- 荧光笔（`StockBrushes.highlighter`，注意 alpha 提交时机）

### Phase 3：同步（依赖进度同步先落地）
- 注记作为独立 delta 随进度同步上传
- LWW：`updatedAt` 时间戳决胜；同页追加不覆盖（Union）

---

## 九、参考资料

- [Ink API 官方文档](https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/about-ink-api) — 最新更新 2026-04-17
- [androidx.ink 版本历史](https://developer.android.com/jetpack/androidx/releases/ink) — 1.0.0 稳定版 2025-12-17
- [低延迟前缓冲区原理](https://android-digest.com/inside-android-stylus-latency-how-front-buffer-rendering-drops-ink) — 详细管线分析
- [chromeos/low-latency-stylus 示例](https://github.com/chromeos/low-latency-stylus) — 完整参考实现
- [Stylus Low Latency by Cedric Ferry](https://medium.com/androiddevelopers/stylus-low-latency-d4a140a9c982) — 前缓冲区 + 运动预测组合讲解
