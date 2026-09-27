# PhoneAct · Android 屏幕识别与操作 MCP 服务

一个跑在真机上的 **Xposed 模块 + 无障碍服务 + Root 通道 + MCP 服务器**，让大模型能"看见"并"操作"手机。

```
┌──────────────────────────────── PhoneAct APK ────────────────────────────────┐
│  MD3 GUI (Compose)                                                          │
│      │                                                                      │
│  ┌───▼─────────────┐   ┌──────────────────┐   ┌──────────────────────────┐  │
│  │ FrameHub        │──▶│ Recognizer       │──▶│ McpServer (NanoHTTPD)    │  │
│  │ 屏幕更新中枢     │   │ 分块+颜色+OCR     │   │ POST /mcp · GET /sse     │  │
│  └───┬─────────────┘   └──────────────────┘   └────────────▲─────────────┘  │
│      │                                                      │                │
│  ┌───▼─────────────┐   ┌──────────────────┐   ┌────────────┴─────────────┐  │
│  │ Capture         │   │ Actions / AppOps │   │ UiTree                   │  │
│  │ a11y|root|录屏   │   │ a11y 手势 / su    │   │ 无障碍节点树              │  │
│  └───┬─────────────┘   └────────┬─────────┘   └────────────▲─────────────┘  │
└──────┼──────────────────────────┼──────────────────────────┼────────────────┘
       │                          │                          │
  AccessibilityService#takeScreenshot   Magisk su(screencap/input/am/pm)   AccessibilityService
       │
  ┌────▼──────────────────── Xposed / LSPosed 注入 ──────────────────────────┐
  │ Activity#onResume         → 精确前台应用/Activity 追踪                    │
  │ ViewRootImpl#performTraversals → 高精度"屏幕内容更新"信号（补无障碍盲区）   │
  │ MediaProjection 授权弹窗   → 自动确认，录屏截屏可无人值守                   │
  └──────────────────────────────────────────────────────────────────────────┘
```

---

## 1. 需求对照

### 1.1 识别

| 需求 | 实现位置 | 说明 |
| --- | --- | --- |
| 分块识别（状态栏 + 主体） | `core/Recognizer.kt → splitBlocks()` | 三块：`status_bar` / `main` / `nav_bar`，高度自动读取 `status_bar_height`、`navigation_bar_height`，可手动覆盖 |
| 每次屏幕内容更新都识别一次 | `core/FrameHub.kt` | 双触发源：无障碍事件置脏 + 32×32 灰度帧差分（阈值 2.5/255）；命中即跑完整识别，`debounceMs` 去抖合并 |
| 识别依据：颜色不一致 + 有文字 | `core/Recognizer.kt` | 见下 |
| 输出屏幕坐标 | `ScreenElement.bounds / center` | 每个元素输出 `[left,top,right,bottom]` 与 `(centerX,centerY)` |
| opencv / ocr | 纯 Kotlin + ML Kit | 颜色部分用**量化直方图 + 连通域**替代 OpenCV（无 native 依赖，APK 更小、更稳）；文字用 **ML Kit 中文 OCR**（离线内置模型，不依赖 GMS） |

**识别流水线**（`Recognizer.recognize`）：

1. **分块** —— 按状态栏 / 主体 / 导航栏切分位图。
2. **颜色不一致检测**（无 OpenCV 的等价实现）
   - 位图按 `analyzeScale` 降采样，颜色量化到 4bit/通道 → 4096 桶直方图；
   - 取占比最高的 1~3 个桶作为**背景候选色**（容忍渐变壁纸）；
   - 逐像素计算到最近背景色的**最大通道色差**，超过 `colorDeltaThreshold` 记入掩码；
   - 掩码上做 4 邻域**连通域**，得到"与背景颜色不一致的组件"；
   - 同行的字符型连通域按水平间距**合并**成词/行级组件。
3. **文字检测** —— 对每块跑 ML Kit OCR，得到带包围盒的文本行；对每个文本框再采样其前景/背景主色，算出该文本的 **colorDelta**。
4. **判定** —— 同时满足 `colorDelta ≥ 阈值` 且 **含文字** 的组件被认定为可操作元素（`source=ocr`）；
   无文字但颜色明显不一致的组件作为图标/按钮候选（`source=color`，可用 `keep_only_text_elements` 关闭）。
5. **无障碍互校** —— 用无障碍节点树补全/校正文本与"可点击性"，IoU>0.3 时合并（`source=accessibility`）。

### 1.2 操作

| 需求 | 实现位置 | 说明 |
| --- | --- | --- |
| 无障碍权限 | `core/Actions.kt` / `core/UiTree.kt` | `dispatchGesture` 手势注入、`ACTION_SET_TEXT`、`performGlobalAction`、节点树读取与按文本点击 |
| root shell (magisk su) | `core/RootShell.kt` | **常驻 su 会话**（哨兵行分帧，避免每次重开进程），提供 `input tap/swipe/keyevent/text`、`screencap`、`am/pm` |
| 屏幕操作 | `Actions.tap/longPress/swipe/path/scroll` | 无障碍优先，失败自动回退 root |
| 应用操作 | `core/AppOps.kt` | 列表 / 启动 / 强停 / 当前前台 / 安装 / 卸载 / 启动任意 Activity |

### 1.3 GUI

`ui/` 下为 **Material Design 3**（Compose Material3 + 动态取色）：总览 / 识别 / MCP / 设置 四个页面，
底部 `NavigationBar`，卡片使用 `surfaceContainerHigh`，色板与排版统一在 `ui/theme/Theme.kt`。

---

## 2. MCP 服务

- 传输：**Streamable HTTP**（`POST /mcp`）+ 兼容旧版 **HTTP+SSE**（`GET /sse` + `POST /messages`）
- 协议版本：`2025-06-18`（兼容 `2025-03-26` / `2024-11-05`）
- 端口默认 `8517`；`bindAll` 打开后局域网可访问，非回环连接需要 `Authorization: Bearer <token>`
- 辅助端点：`GET /`（说明页）、`GET /health`、`GET /screen.png`、`GET /screen.json`

### 客户端配置

```json
{
  "mcpServers": {
    "phoneact": { "url": "http://<手机IP>:8517/mcp" }
  }
}
```

或经 adb 端口转发后使用本地地址：

```bash
adb forward tcp:8517 tcp:8517
# → http://127.0.0.1:8517/mcp
```

### 工具清单

| 分类 | 工具 |
| --- | --- |
| 状态 | `device_status` `screen_state` `settings_get` `settings_set` `logs_tail` |
| 识别 | `screen_recognize` `screen_model` `screen_wait_update` `screen_screenshot` |
| 操作 | `ui_tap` `ui_tap_text` `ui_long_press` `ui_swipe` `ui_scroll` `ui_drag_path` `ui_set_text` `ui_key` `ui_global` |
| 输入 | `ui_clipboard_set` `ui_clipboard_get`（配合 `ui_key paste` 输入中文） `ui_dump_tree` |
| 应用 | `app_list` `app_launch` `app_stop` `app_current` `app_install` `app_uninstall` |
| 底层 | `shell_exec`（root） |

资源：`phoneact://status`、`phoneact://screen/model`、`phoneact://settings`。

---

## 3. 构建

前置：**JDK 17 或 21**（AGP 8.9 不支持 JDK 25）、Android SDK（platform 35）。

```bash
cp local.properties.example local.properties   # 填入本机 SDK 路径
./gradlew :app:assembleDebug                   # 或 gradle :app:assembleDebug
```

Gradle 默认 JDK 过新时，显式指定：

```bash
./gradlew -Dorg.gradle.java.home=/path/to/jdk21 :app:assembleDebug
```

依赖拉取：`settings.gradle.kts` 把阿里云镜像放在官方源之前（实测国内直连 `services.gradle.org`／`dl.google.com`
会卡死在下拉依赖上）；海外环境可删掉其中的 `maven.aliyun.com` 行。

> 本仓库不含任何密钥：`local.properties`、keystore、`.env` 均已被 `.gitignore` 排除；
> MCP 访问令牌由 App 首次启动时随机生成，只存在于设备本地 SharedPreferences。

## 4. 安装与授权

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. **Root**：首次运行会弹 Magisk 授权，允许即可（也可用
   `su -c 'magisk --sqlite "INSERT OR REPLACE INTO policies (uid,policy,until,logging,notification) VALUES (<uid>,2,0,1,1)"'` 预授权）。
2. **无障碍**：设置 → 无障碍 → PhoneAct → 开启。
3. **Xposed（可选）**：LSPosed → 模块 → 勾选 PhoneAct → 作用域勾选
   `系统框架`、`SystemUI`、以及需要自动化的目标应用 → 重启。
   未启用时功能自动降级到无障碍 + root。

## 5. 实测结论

真机（Redmi / Android 16 / API36 / Magisk + LSPosed）实测通过，详见 [`docs/VERIFICATION.md`](docs/VERIFICATION.md)：
分块边界与系统一致、颜色+文字判定输出坐标、识别→点击→等待更新→再识别全链路闭环、
Xposed 模块被 LSPosed 注入并回传状态（bridge 102 / 3 个 hook 生效）。

## 6. 目录结构

```
app/src/main/java/com/dsh/phoneact/
├── core/        # Lg Prefs Model RootShell Capture MediaProjectionCapture Ocr
│                # Recognizer FrameHub Actions AppOps UiTree ActCore
├── mcp/         # McpServer SseStreamResponse McpProtocol McpTools BootReceiver
├── service/     # ActAccessibilityService CaptureService
├── xposed/      # PhoneActModule XposedStatus XposedEventReceiver XposedBridgeProvider
└── ui/          # PhoneActRoot Components theme/ screens/
xposed-stubs/    # XposedBridge API 编译期桩（compileOnly，不打包进 APK）
```