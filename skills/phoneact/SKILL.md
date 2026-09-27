---
name: phoneact
description: 通过 MCP 操作一台（已 root 的）安卓手机 —— 分块屏幕识别拿坐标、坐标/文本点击、应用操作，以及用动态 hook 替换目标 App 的录音内容。当用户要求"操作手机 / 自动化某个 App / 帮我发消息 / 看看屏幕上有什么"时使用。
---

# PhoneAct · 用 MCP 操作安卓手机

## 0. 先建立认知

设备上同时有三条通道，**按下面的顺序选**：

| 通道 | 适合 | 入口 |
| --- | --- | --- |
| **无障碍节点** | 有标准控件树的应用，最省事 | `ui_dump_tree` / `ui_tap_text` / `ui_set_text` |
| **视觉识别 + 坐标** | 需要精确点位、批量/循环、无障碍树被屏蔽的 App（微信、QQ） | `screen_recognize` → `ui_tap` / `ui_tap_frame` |
| **动态 hook**（可选增强） | 目标 App 的语音交互只认语音，或要把它的录音内容换成指定 TTS 语音 | `clip_synth` → `hook_arm` |

开干前先 `device_status` 确认 root / 无障碍 / Xposed / 动态 hook 是否可用。

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

## 2. 动态 hook：把任意 App 的录音内容换成我们的 TTS 语音

hook 装在**框架层**（`android.media.AudioRecord`），不依赖任何 App 的内部结构，
所以**只要 App 在 LSPosed 作用域里**，它的录音就会被同一套运行时开关控制。

### 2.1 四步

```
1) clip_synth { texts:["第一句", "确认"] }   # 合成语音队列（多轮按顺序取用）
2) hook_arm { target_package:"com.tencent.mm" }  # 武装；留空 = 作用域内所有 App
3) 让目标 App 开麦（自己唤起 / ui_* 点麦克风 / 播放语音）
4) hook_disarm                                  # 收工卸下
```

| 原语 | 作用 |
| --- | --- |
| `clip_synth {texts, rate?}` | 合成 16k/16bit 单声道 PCM 队列（本机 TTS，失败回退 PC 端 TTS） |
| `clip_queue` | 看队列：段数、每段时长、是否已武装 |
| `hook_arm {target_package?}` | 武装录音替换（队列为空会拒绝） |
| `hook_disarm` | 卸下并清空队列、清除目标包过滤 |
| `hook_status` | 状态 + 哪些进程已挂上 hook |
| `hook_log {lines}` / `hook_trace {lines}` | 确认某个 App 有没有开麦、语音有没有真被替换 |

### 2.2 关键性质

- **没武装时 hook 完全不碰麦克风数据**，等于不存在。所以可以常驻，不必担心副作用。
- 武装后**每次开麦**按轮次取下一段：第 1 次录音播第 1 段，第 2 次录音播第 2 段……
  队列用完后自动不再替换（不会无限循环同一句）。
- `target_package` 留空 = 作用域内**所有** App 一起生效；指定包名 = 只对该 App 生效。
- **作用域变更（新增 App）只需做一次**，且要重启该 App 进程（模块在 fork 时加载）；
  之后 arm / disarm / 换文本都是**即时生效、不用重载**。
- 每段 ≤ 3.5 秒（多数 App 的录音窗口只有几秒，系统会自动补前导静音与尾部静音）。

### 2.3 什么时候值得用

- 目标能力只接受语音输入，文字会被拒（部分语音助手类能力）。
- 想给某个 App 喂一段可控的语音，而不是用真实麦克风。
- 反过来说：**能用 ui_* 完成的，就别用 hook** —— 坐标通道更好排查。

---

## 3. 典型流程：给微信某人发消息

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

## 4. 其它常用工具

| 分类 | 工具 |
| --- | --- |
| 应用 | `app_list` `app_launch` `app_stop` `app_current` `app_install` `app_uninstall` |
| 设备 | `device_status` `screen_state` `shell_exec`(root) `logs_tail` |
| 识别调参 | `settings_get` `settings_set`（颜色阈值、分块开关、框体检测…） |
| 动态 hook | `clip_synth` `clip_queue` `hook_arm` `hook_disarm` `hook_status` `hook_log` `hook_trace` |
| TTS | `tts_status` `tts_self_test`（PC 端 SAPI 服务，动态 hook 合成失败时的回退） |

## 5. 排障顺序

1. `device_status` —— root / 无障碍 / Xposed / 动态 hook 是否都在
2. `hook_status` + `hook_log` —— 看目标进程挂上 hook 没有、开麦没有、语音载入没有
3. `screen_recognize` —— 看当前真实画面，别猜
4. `logs_tail` —— 应用自身日志
