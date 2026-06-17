# Readflow

自用阅读器，灵感来自静读天下。三端同步：Android · HarmonyOS · Web。

## 特性

- 接入 Calibre 书库（局域网 Content Server）
- EPUB / PDF / MOBI 阅读
- 三端统一阅读进度（待实现）

## 平台

| 平台 | 目录 | 技术栈 |
|------|------|--------|
| Android | `android/` | Kotlin · Jetpack Compose · Ktor |
| HarmonyOS | `harmony/` | ArkTS · ArkUI |
| Web | `web/` | React 18 · TypeScript · epubjs · Vite |

## Calibre 接入

在本地启动 Calibre Content Server（`calibredb serve` 或 GUI → Preferences → Sharing over network），
然后各端配置 `baseUrl` 为局域网地址，如 `http://192.168.1.x:8080`。

详见 `shared/calibre/api.md`。

## 开发

```bash
# Web
cd web && npm install && npm run dev

# Android
# 用 Android Studio 打开 android/

# HarmonyOS
# 用 DevEco Studio 打开 harmony/
```
