# Development Guide

## 环境准备

### 前置依赖

| 工具 | 用途 |
|------|------|
| Node.js 20+ | Web 端开发 |
| Android Studio | Android 端开发 |
| DevEco Studio | HarmonyOS 端开发 |
| Calibre | 本地书库服务 |

### 启动 Calibre 书库

```bash
# CLI 模式
calibre-server --port 8080 --cors /path/to/library

# GUI 模式
# Preferences → Sharing over network → Start server
# 勾选 "Allow CORS"
```

## 常用命令

| 平台 | 任务 | 命令 |
|------|------|------|
| Web | Dev server | `cd web && npm run dev` |
| Web | Build | `cd web && npm run build` |
| Web | Type check | `cd web && npx tsc --noEmit` |
| Android | Build debug APK | `cd android && ./gradlew assembleDebug` |
| Android | Run tests | `cd android && ./gradlew test` |
| HarmonyOS | Build | DevEco Studio → Build → Build Hap |

## 项目规范

### 语言约定

- **Chinese**：用户界面字符串、UI 标签
- **English**：代码、注释、提交信息、日志
- 例：按钮文字 `"开始阅读"`，变量名 `startReading`，commit `feat: add TxtVirtualPager`

### 代码风格

- **Web**：TypeScript strict mode，React hooks 优先，避免 class component
- **Android**：Kotlin 惯用写法，Compose 优先于 XML，协程优先于回调
- **HarmonyOS**：ArkTS 规范，`@State`/`@Prop` 数据流

### 提交规范

```
feat: <描述>       # 新功能
fix: <描述>        # 修复
refactor: <描述>   # 重构
docs: <描述>       # 文档
chore: <描述>      # 杂项
```

## 架构约束

### 改 Calibre API shape 的流程

1. 改 `shared/api/calibre-contract.ts`（唯一真相来源）
2. 同步 `web/src/services/calibre.ts`
3. 同步 `android/.../CalibreClient.kt`
4. 同步 `harmony/.../CalibreService.ets`

### EPUB 渲染开发

涉及 EPUB 渲染前**必须**阅读 `.claude/skills/linreads-epub/SKILL.md`。当前 Web 仍是 epubjs，目标架构为 `@likecoin/epub-ts`。

### 进度同步开发

涉及同步前**必须**阅读 `.claude/skills/linreads-sync/SKILL.md`，明确 LWW/Union 策略。

### CORS 注意事项

- Web dev 模式：Vite proxy 自动处理（配置在 `web/vite.config.ts`）
- Web 生产部署：需在 nginx/CDN 层配置代理
- Android/HarmonyOS：直连 LAN IP，需 Calibre 服务端开 `--cors`
- Calibre 不支持 OPTIONS 预检请求，只支持简单 CORS 请求

## 测试

### Web

```bash
cd web && npx vitest          # 运行测试（如已配置）
cd web && npx tsc --noEmit    # 类型检查
```

### Android

```bash
cd android && ./gradlew test   # 单元测试
```

Android 端设计强调可测试性：MVI 架构 + Engine interface 完全可 mock。

### HarmonyOS

DevEco Studio 内置测试运行器。

## Skill 自动触发规则

项目配置了 9 个 skill，触及对应范围时自动激活（详见 `CLAUDE.md`）：

| Skill | 触发范围 |
|-------|---------|
| `linreads-dev` | 任何代码改动 |
| `linreads-epub` | EPUB/CFI/epubjs/epub-ts 相关 |
| `linreads-sync` | 进度同步相关 |
| `accessibility` | UI 文件改动 |
| `design-audit` | 视觉/UX 审查 |
| `tdd` | 写测试 |
| `systematic-debugging` | Bug 排查 |

## 环境变量

### Web 端

`web/.env.example` 查看模板，复制为 `.env.local` 修改：

```env
VITE_CALIBRE_URL=http://192.168.1.x:8080
```

### Android 端

`CalibreClient` 初始化时传 `baseUrl`，来源待接 Settings SharedPreferences。

### HarmonyOS 端

`CalibreService.ets` 中 `BASE_URL` 当前默认 `192.168.1.1:8080`，待接 Settings 页持久化配置。

---

_参考：_ [CLAUDE.md](../../CLAUDE.md) · [Architecture](Architecture.md) · [Calibre API](Calibre-API.md)
