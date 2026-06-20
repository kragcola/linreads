# Research: Stylus & Ink

> Android 手写笔注记可行性调研
> 调研日期：2026-06-17
> 结论：**可行，推荐使用 androidx.ink（1.0.0 已正式发布）**

## 技术选型

| 方案 | 延迟 | 复杂度 | 笔压/倾斜 | 防掌误触 | 序列化 | 适合度 |
|------|------|--------|---------|---------|--------|--------|
| **A. androidx.ink** | 最低（前缓冲区渲染）| 低 | ✅ 内置 | ✅ 系统级 | ✅ 内置 | ★★★★★ |
| B. Canvas + Path（自实现）| 中（标准双缓冲）| 高 | 手动读 MotionEvent | 手动过滤 | 手动 | ★★★ |
| C. GLSurfaceView 手写 | 低（OpenGL）| 极高 | ✅ | 手动 | 手动 | ★★ |

## androidx.ink 模块结构

```
androidx.ink
  ├── ink-authoring          # InProgressStrokesView：实时渲染中的笔画（前缓冲区）
  ├── ink-strokes            # Stroke / StrokeInputBatch 核心数据类型
  ├── ink-brush              # Brush：画笔外观（粗细、颜色、纹理、荧光笔）
  ├── ink-geometry           # 几何运算（交集、覆盖 → 选中/擦除工具）
  ├── ink-rendering          # CanvasStrokeRenderer：渲染完成笔画到 View
  ├── ink-storage            # StrokeInputBatch 序列化（紧凑 proto）
  └── ink-nativeloader       # C++ 底层加载器（自动依赖）
```

## 低延迟原理

### 前缓冲区路径 vs 标准路径

```
标准路径（~30–50ms @ 60Hz）：
  MotionEvent → GPU 写入 back buffer → SurfaceFlinger 等 vsync → 合成 → 显示

前缓冲区路径（~9ms @ 60Hz）：
  MotionEvent → 直接写入 front buffer → 立即可见
```

跳过 SurfaceFlinger 合成等待（~16.6ms）。`InProgressStrokesView` 封装整套逻辑。

### MotionEventPredictor

基于近几帧输入预测下一帧笔位置，提前绘制。两者叠加后感知延迟降至 <10ms。

## 叠加架构

```
FrameLayout (AnnotationContainer)
  ├── WebView / RecyclerView（文档渲染层）
  ├── CanvasView（已完成笔画，标准双缓冲 View）
  └── InProgressStrokesView（进行中笔画，前缓冲区，透明叠加）
```

### 输入路由（笔/手指自动分离）

```kotlin
override fun onTouchEvent(e: MotionEvent): Boolean {
    return when (e.getToolType(0)) {
        MotionEvent.TOOL_TYPE_STYLUS,
        MotionEvent.TOOL_TYPE_ERASER -> {
            inkLayer.handleStylusEvent(e)   // 笔 → 手写层
            true
        }
        else -> false  // 手指 → 文档 WebView（滚动/点击）
    }
}
```

手指滚动文档、笔书写注记，两者互不干扰，无需切换模式。

## 存储方案

```kotlin
@Entity(tableName = "annotations")
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val docId: String,         // SHA-256(文件绝对路径)前16字符
    val pageRef: String,       // EPUB: chapterId；PDF: "page:5"
    val strokeData: ByteArray, // ink-storage 序列化的 StrokeInputBatch
    val updatedAt: Long,       // LWW 时间戳
)
```

`ink-storage` 序列化比传统 `List<PointF>` JSON 小约 70%。

## 实现路径

### Phase 1：基础手写
- 引入 `ink-authoring` + `ink-strokes` + `ink-rendering`
- `InProgressStrokesView` 叠加在文档 WebView 上
- 笔/手指自动路由
- Room 存储已完成笔画
- 支持撤销/重做

### Phase 2：工具栏
- 画笔颜色/粗细选择（`Brush` API）
- 橡皮擦（`TOOL_TYPE_ERASER` + `ink-geometry`）
- 荧光笔（`StockBrushes.highlighter`）

### Phase 3：同步
- 注记作为独立 delta 随进度同步上传
- LWW：`updatedAt` 时间戳决胜；同页追加不覆盖（Union）

## 已知限制

| 限制 | 影响 | 处理方式 |
|------|------|---------|
| 前缓冲区不支持 alpha 混合 | 半透明荧光笔进行中时有色差 | 提交后再以正确混合模式重绘 |
| HarmonyOS 端 | 此方案仅 Android | HarmonyOS 需独立方案 |

---

_参考：_ [docs/research/android-stylus-feasibility.md](../research/android-stylus-feasibility.md) · [Platform: Android](Platform-Android.md)
