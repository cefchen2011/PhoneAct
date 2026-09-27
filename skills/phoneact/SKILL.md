---
name: phoneact
description: 通过 MCP 操作一台（已 root 的）安卓手机 —— 分块屏幕识别拿坐标、坐标/文本点击、应用操作，以及在超级小爱擅长的场景把任务交给它执行。当用户要求"操作手机 / 自动化某个 App / 帮我发消息 / 看看屏幕上有什么"时使用。
---

# PhoneAct · 用 MCP 操作安卓手机

## 0. 先建立认知

设备上同时有三条操作通道，**按下面的顺序选**：

| 通道 | 适合 | 入口 |
| --- | --- | --- |
| **超级小爱**（语音） | 有语义、需云端能力：发消息、打车、点外卖、设提醒、查天气、控制智能家居 | `xiaoi_tts_set` → `xiaoi_voice_task` |
| **视觉识别 + 坐标** | 需要精确点位、批量/循环、读取具体控件、无障碍树被屏蔽的 App（微信、QQ） | `screen_recognize` → `ui_tap` / `ui_tap_frame` |
| **无障碍节点** | 有标准控件树的应用，最省事 | `ui_dump_tree` / `ui_tap_text` |

开干前先 `device_status` 确认 root / 无障碍 / Xposed / 超级小爱 是否可用。

---

## 1. 屏幕识别（怎么拿到坐标）

`screen_recognize {force:true}` 返回**分块**结果（`status_bar` / `main` / `nav_bar`），每个元素带：

- `text` —— 文字内容（空 = 无文字）
- `bounds` / `center` —— **屏幕坐标，点击就用 center**
- `colorDelta` —— 与背景的颜色不一致度
- `kind` —— `text` / `icon` / `node` / `input` / `button` / `container`
- `source` —— `ocr`（视觉识别文字）/ `accessibility`（无障碍节点）/ `color`（纯颜色块）/ `frame`（几何框体）

**判定依据**：与背景颜色不一致（Δ≥阈值）**且**含有文字的组件。
低对比度的实心矩形（输入框/按钮）走 `frame` 通道单独检出，例如微信输入框与工具条亮度差只有 11。

### 常用动作

| 目的 | 调用 |
| --- | --- |
| 按文本点 | `ui_tap_text {text:"登录"}` |
| 按坐标点 | `ui_tap {x,y}` |
| 点输入框/按钮（无文字） | `ui_tap_frame {kind:"input"}` |
| 输入中文 | `ui_clipboard_set` → `ui_key {key:"paste"}` |
| 等界面刷新 | `screen_wait_update {since_seq, timeout_ms}` |

---

## 2. 操作手机时：默认把日志带回来

- 执行类工具（`xiaoi_* `）**返回里自带 `trace`**：被注入进程实时回传的 hook 轨迹
  （AudioRecord 注入 / 输入层篡改 / origin 覆盖 / 语音载入）。
- 需要更长的轨迹用 `xiaoi_trace {lines}`。
- 执行后返回里还有：
  - `screen` —— 结束时屏幕上的文字（用来核对"到底做没做成"）
  - `verdict` —— `success` / `failed` / `unknown`（按关键词判定）
  - `verdictHit` —— 命中的关键词

**别只看工具返回的 ok**，一定看 `verdict` 和 `screen`。

---

## 3. 走超级小爱（只认语音）

小爱对"发微信/打电话"这类能力**只接受语音指令**，文字会被回绝
（"当前仅支持语音对话方式"）。所以流程是：**合成语音 → 注入它的麦克风**。

### 3.1 三步走

```
xiaoi_tts_set { texts: ["给微信好友sunshine发消息说你好", "确认"] }   # 1) 编排语音内容
xiaoi_voice_task { use_prepared: true, wait_ms: 30000 }              # 2) 触发
xiaoi_trace { lines: 40 }                                            # 3) 需要时看轨迹
```

- `texts` 就是**要注入的内容，按轮次排列**。第 1 轮说指令，后续轮次说追问的答案。
- 语音由 PC 端 Windows SAPI 服务合成（`tts_status` 可探活；地址在设置里配）。

### 3.2 **多轮是常态，别只给一轮**

小爱的技能会追问：先问"发给谁/说什么"，**页面跳转后**再问"确认发送吗"。

我们的处理方式（已内置，你只要按需给 `texts`）：

1. **语音队列**：每次小爱重新开始录音（`AudioRecord.startRecording`），
   自动取队列的下一段。所以页面跳转后自然会说"确认"。
2. **没跳转就补点麦克风**：如果等了一会儿页面**没有跳转**（说明小爱没往下走），
   会自动补点一次小爱悬浮条上的**麦克风图标**（约在条的 77% 处），
   开下一轮 —— 队列里备好的"确认"这时才被说出去。最多补 3 次。

> 因此：**多轮任务的 `texts` 一定要把后续答案排进去**，
> 最常见的第二段就是 `"确认"`。

### 3.3 要点与坑

- **来源必须像语音**：小爱按 `QueryOrigin` 做能力门禁 ——
  文字输入框是 `QueryEditBar`（会被拒），语音按钮是 `VoiceButton`（放行）。
  工具会自动把 origin 覆盖成 `VoiceButton`。
- **录音窗口约 5 秒**：单段语音别太长（`xiaoi_tts_set` 会返回每段 `durations`，
  建议每段 ≤ 3500ms）。
- **改了 APK 要重启小爱进程**：模块代码在进程 fork 时加载，
  覆盖安装后要 `am force-stop com.miui.voiceassist` 才会用上新代码。
- **小爱答非所问/明确拒绝时不要重试**，直接切 `ui_*` 通道。

---

## 4. 典型流程

### 4.1 给微信某人发消息（无字典 + 小爱语音）

```
1. xiaoi_tts_set { texts:["给微信好友<名字>发消息说<内容>", "确认"] }
2. xiaoi_voice_task { use_prepared:true, wait_ms:30000 }
3. 看返回的 verdict / screen：
   - verdict=success  -> 完成
   - verdict=unknown  -> 用 screen_recognize 看当前画面再决定
   - 小爱拒绝          -> 改走 4.2
```

### 4.2 同样的事但走坐标（小爱不配合时）

```
app_launch {package:"com.tencent.mm"}
screen_recognize                        # 找会话/搜索入口
ui_tap_text {text:"<联系人>"}            # 无障碍命中不到就走识别坐标
ui_tap_frame {kind:"input"}             # 微信输入框：无文字、低对比度，必须用框体检测
ui_clipboard_set {text:"<内容>"}
ui_key {key:"paste"}                    # 中文只能靠剪贴板
ui_tap {x,y}                            # 发送按钮坐标（用 screen_recognize 拿）
```

> 微信**屏蔽了无障碍节点树**（整窗只有 1 个节点），`ui_tap_text` / `ui_set_text` 会失效，
> 必须走"视觉识别 + 坐标"。这也是把视觉识别做成一等公民的原因。

---

## 5. 其它常用工具

| 分类 | 工具 |
| --- | --- |
| 应用 | `app_list` `app_launch` `app_stop` `app_current` `app_install` `app_uninstall` |
| 设备 | `device_status` `screen_state` `shell_exec`(root) `logs_tail` |
| 识别调参 | `settings_get` `settings_set`（颜色阈值、分块开关、框体检测、TTS 服务地址…） |
| 小爱 | `xiaoi_status` `xiaoi_tts_set` `xiaoi_tts_queue` `xiaoi_voice_task` `xiaoi_act` `xiaoi_trace` `xiaoi_inject_log` |

## 6. 排障顺序

1. `device_status` —— root / 无障碍 / Xposed / 小爱 是否都在
2. `xiaoi_trace` —— 看注入到底跑到哪一步（有没有 `injected=true`、有没有 `输入入口`）
3. `screen_recognize` —— 看当前真实画面，别猜
4. `logs_tail` —— 应用自身日志
