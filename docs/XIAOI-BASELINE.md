# 超级小爱语音链路 · 真人操作基准日志分析

采集方式：清空 logcat + 记录 LSPosed 模块日志行号，用户**全程真人语音**操作一次
"给微信好友发消息"，走完多轮直到发送完成。

原始日志：`baseline.log`（logcat 全量）+ 模块日志 5609 行之后的新增部分。

---

## 1. 成功路径的完整时序

```
19:43:30.668  QueryOrigin setQueryOrigin = com.miui.voiceassist.ACTION_VOICE_START_VOICEASSIST
                                           &&android.intent.action.ASSIST
                                           &&double_click_fullscreen_gesture_line     ← 双击手势条唤起
19:43:30.719  AudioRecord.startRecording rate=16000 ch=4 encoding=16bit source=1
19:43:36.245  y00.u0.setQueryText args[0]=给SUNSHINE发微信说你好                        ← 第1轮 ASR 结果
19:43:36.481  VA_WeChatLanguageControl: setShouldStartVoiceControl: true
19:43:36.481  VA_WeChatLanguageControl: Starting voice control
19:43:36.481  VA_WeChatLanguageControl: Exit continuous dialog for WeChat voice control
19:43:36.497  VA_WeChatLanguageControl: onEngineAudioBuffer ... data size: 2048
19:43:36.497  VA_WeChatLanguageControl: Sending voice buffer to WeChat, size: 2048
19:43:36.498  [ASR_CACHE] Loaded asr.pcm file, size: 172032 bytes
19:43:36.513  nativeloader: Load .../libopen_voice_control_sdk
19:43:41.719  QueryOrigin setQueryOrigin = VoiceButton
19:43:41.802  AudioRecord.startRecording                                                ← 第2轮录音
19:43:44.203  y00.u0.setQueryText args[0]=确认                                           ← 第2轮 ASR 结果
19:43:45.810  A2ACardActivity 收尾 → 回到 com.tencent.mm
```

## 2. 三个决定性结论

### 2.1 微信发消息**不用小爱自己的 ASR**，而是把音频转交给微信

```
VA_WeChatLanguageControl: Sending voice buffer to WeChat, size: 2048
nativeloader: Load .../libopen_voice_control_sdk
layername=com.tencent.mm/com.tencent.mm.open_voice_control.card.A2ACardActivity
```

小爱只负责"听懂要发消息给谁"并打开微信的语音控制卡片；
真正的消息内容由**微信自己的** `open_voice_control_sdk` 识别。

**对小爱语音通道的意义**：我们注入到 `AudioRecord` 的音频，
会顺着这条转发链一路到微信，所以**注入一次就能覆盖小爱 + 微信两侧**。

### 2.2 音频会被缓存成文件再回放

```
[ASR_CACHE] File reset for new session: .../files/weChatLanguageControl/asr.pcm
[ASR_CACHE] Loaded asr.pcm file, size: 172032 bytes
```

每轮会话开始时重置该文件，交棒给微信用的是**缓存文件**而不是实时麦克风。
（也意味着"音频只播一遍"就够，不需要为微信侧重复注入。）

### 2.3 多轮就是多轮，且间隔约 5 秒

- 第 1 轮 ASR 结果：`19:43:36.245`
- 第 2 轮 `startRecording`：`19:43:41.802` → **间隔约 5.5 秒**
- 第 2 轮 ASR 结果：`19:43:44.203`

这正好验证了 `step_ms = 5000` 这个节奏：**每一步留 5 秒**是贴近真实交互的取值。

## 3. 与自动流程的差异（据此定位缺口）

| 环节 | 真人 | 我们的自动流程 | 状态 |
| --- | --- | --- | --- |
| 唤起 | 双击手势条 | `am start -a ASSIST` | origin 不同，但都会被覆盖成 `VoiceButton` |
| 第 1 轮 | 真人说整句 | TTS 合成整句注入 | ✅ 已识别（`输入入口` 可见） |
| 交棒微信 | 小爱转发音频 | 同一条链 | ✅ 应同样生效（音频来自我们的注入） |
| 第 2 轮 | 真人说"确认" | 队列第 2 段 | ✅ 已看到新一轮 `startRecording` |
| 结果 | A2ACardActivity 收尾 | —— | 需核对微信侧是否真发出 |

**下一步要核对的**：第 2 轮之后是否也出现
`Starting voice control` → `Sending voice buffer to WeChat` → `A2ACardActivity` 收尾。
如果没出现，说明卡在"小爱没交棒给微信"这一步，而不是语音内容的问题。

## 4. 成功判定特征（用于自动填 verdict）

出现以下组合可判 `success`：

- `VA_WeChatLanguageControl: Starting voice control`
- `Sending voice buffer to WeChat`
- `com.tencent.mm/.open_voice_control.card.A2ACardActivity` 出现后**正常收尾**（transition 结束）

出现以下可判 `failed`：

- `微信发消息…当前仅支持语音对话方式`（origin 不是语音）
- 卡在 `A2ACardActivity` 不退出，或 小爱回 `抱歉/无法/不支持`
