# 书架拖拽与建组 — 验证报告

**日期**：2026-07-14
**状态**：Android 逻辑、Room、编译与平板模拟器验证通过

## 当前交互

| 操作 | 触发 | 结果 |
|------|------|------|
| 打开书籍 | 点击卡片 | 打开阅读器 |
| 书籍菜单 | 点击封面左上 48dp 触摸区 | 改名、建组说明、删除等 |
| 重排 | 长按拖动并越过目标中心 | 沿途书籍顺移，松手后保存顺序 |
| 建组 | 将两张封面的四边差值都拖入 `<20dp` | 立即显示组合封面与“松手建组”，松手后输入组名 |
| 自动滚动 | 拖到网格上下 72dp 边缘区 | 按边缘距离与停留时间逐帧加速 |

建组不再使用 700ms dwell。该判定直接参考静读天下主书架：

- `moonreader-decompiled/sources/com/flyersoft/moonreaderp/ActivityMain.java:4731`：逐项交换并调用 `notifyItemMoved`。
- `moonreader-unpacked/smali_classes2/com/flyersoft/moonreaderp/ActivityMain$ShelfTouchHelper.smali:108`：拖动项与候选项四边差值严格 `<20dp` 时进入合并预览。
- `moonreader-decompiled/sources/com/flyersoft/moonreaderp/ActivityMain.java:4777`：松手阶段提交合并。
- `moonreader-unpacked/res/layout/shelf_grid_item.xml:16`：封面右下圆形百分比进度。

## 手感与视觉

- 拖动源常态 `1.04x`；进入建组预览后 `0.84x / 0.82α`。
- 目标卡片显示组合封面、描边与轻微缩放；拖动源书名在重合阶段隐藏，避免双层文字。
- 普通让位动画 `240ms`，释放回落 `200ms`，均使用 `FastOutSlowInEasing`。
- 拖动项保留 `animateItem` 节点但使用 `snap` placement，避免释放后从列表底部重新弹入。
- 作者行和右上无语义书签已移除；阅读进度改为封面右下圆形百分比。

## 数据一致性

- 新组由一条受条件保护的 Room `UPDATE` 同时写入两本书。
- 只有 source/target 都仍存在且 `collectionName IS NULL` 时才允许更新两行。
- 新建组、移入已有组与导入/upsert 共用 `shelfWriteMutex`，避免整行 REPLACE 覆盖刚写入的组名。
- 新组 UI 不再提前插入乐观 bundle；同名组、写入失败和目标状态变化都不会产生重复 LazyGrid key 或永久假状态。
- 建组失败通过书架 Snackbar 提示“建组失败：…”。
- 移入已有组保留即时合并预览，但等待受影响行校验；失败会回滚最新快照并显示 Snackbar，成功只接收已确认包含该书的组快照。
- 拖拽/释放动画期间收到的新 Room 快照会暂存，并在交互结束后补同步。
- 重排保留即时视觉反馈，但数据库写入失败时会回滚到最新权威快照并显示 Snackbar。
- 拆组和书组改名不再提前改动本地 key/列表；DAO 返回 0 行或写入失败时会保持原界面并给出可恢复提示。

## 自动化验证

```bash
./gradlew :core:ui:testDebugUnitTest
./gradlew :core:database:testDebugUnitTest
./gradlew :features:library:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
./gradlew :app:lintDebug :app:assembleDebug
```

覆盖点包括：末尾插槽、最右列换位、中心阈值、严格四边建组判定、Room 两行原子更新、目标缺失/已移组时零写入、单书移组受影响行、仓储互斥及 ViewModel 成功/失败回调。

最终结果：`core:ui` 33、`core:database` 59、`features:library` 22、`features:reader` 83 个单元测试全部通过，0 failure / 0 error。

## 模拟器验证

- 800×1280dp 平板布局：倒数第二本可换到最右列，强制停止并重启后顺序保持。
- 精确封面重合会先显示组合封面预览，松手后才弹出命名框。
- 单书拖入已有 2 本书组后立即显示“共 3 本”，强制停止并冷启动后仍保持 3 本，证明移组持久化成功。
- 取消命名后仍为 15 本单书、0 个书组，未污染数据库。
- 验证用书组拆组后再次冷启动，恢复为 15 本单书、0 个书组。
- 释放录屏逐帧检查未出现旧版“从底部弹入”。

## 平台状态

Android ✅ | HarmonyOS N/A（本轮仅 Android 书架） | Web N/A（本轮仅 Android 书架）

## 待决策的书组身份

当前数据模型使用 `collectionName` 同时作为书组身份和显示名，因此新建/改名为已有名称会合并书组。静读天下使用独立稳定身份，允许同名书组并存。本轮未在未确认产品语义前改动该契约。
