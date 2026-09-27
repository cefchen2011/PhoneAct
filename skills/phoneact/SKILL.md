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

### 3.1 原语 + 你来编排

注入是**动态 hook**：`xiaoi_inject_arm` 武装后，**任何**小爱的录音会话都会按轮次自动取用队列；
用完 `xiaoi_inject_disarm` 卸下。中间每一步由**你**决定，工具不会替你重试。

| 原语 | 作用 |
| --- | --- |
| `xiaoi_inject_arm {texts}` | 合成语音队列并武装动态 hook |
| `xiaoi_inject_disarm` | 卸下并清空队列 |
| `xiaoi_mic_tap` | 点小爱悬浮条的麦克风图标（**补点与否由你决定**） |
| `xiaoi_observe {wait_ms}` | 等一会儿，返回 `verdict` / `screen` / `trace`，**不做任何重试** |
| `xiaoi_trace {lines}` | 拉 hook 轨迹 |

### 3.2 推荐流程（agent 编排）

```
1) xiaoi_inject_arm { texts: ["给SUNSHINE发微信说你好", "确认"] }
2) 唤起小爱：双击底部手势条 —— 连调两次 ui_tap
      x = 屏宽/2 , y = 屏高 × 0.99
   （必须走这条路：只有它产生 wake origin 里的 double_click_fullscreen_gesture_line）
3) xiaoi_observe { wait_ms: 8000 }        # 给小爱理解 + 跳转的时间
4) 看返回的 screen / verdict：
      · 有进展（进了微信、出现确认卡）      -> 继续 observe
      · 没进展                              -> xiaoi_mic_tap 再 observe（这就是"补点/重发"）
      · 每轮之间留 5 秒左右；最多补 2~3 次
5) 结束时 xiaoi_inject_disarm
```

**为什么补点要由你决定**：小爱的技能多轮且分支多（有时要确认、有时要选联系人、有时直接完成），
写死的重试策略反而容易点错。把它交给观察-决策循环更稳。

### 3.2.1 语音内容怎么排（`texts`）

- 第 1 段 = 完整指令，例如 `给SUNSHINE发微信说你好`（**开头别省字**，见 3.4 的坑）
- 第 2 段起 = 追问的答案，最常见就是 `确认`
- 每段 ≤ 3.5 秒（录音窗口约 5 秒，系统会自动补前导静音与尾部静音）

### 3.3 微信发消息的真实机制（真人日志实测）

小爱**不用自己的 ASR 发微信消息**，而是把音频转交给微信：

```
小爱自己的 ASR: setQueryText("给SUNSHINE发微信说你好")
  -> VA_WeChatLanguageControl: Starting voice control
  -> Sending voice buffer to WeChat        （音频转发）
  -> Load libopen_voice_control_sdk         （微信自己的语音控制）
  -> com.tencent.mm/.open_voice_control.card.A2ACardActivity
```

- 音频会被缓存成 `files/weChatLanguageControl/asr.pcm` 再回放给微信，
  **所以注入一次就能覆盖小爱 + 微信两侧**，不必为微信重复注入。
- 因此失败时先看**有没有出现 `Starting voice control` / `Sending voice buffer to WeChat`** ——
  这一步没出现，说明卡在"小爱没交棒给微信"，而不是语音内容不对。
- 判成功的特征：`A2ACardActivity` 出现后正常收尾。
- 详见 `docs/XIAOI-BASELINE.md`。

### 3.4 要点与坑

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