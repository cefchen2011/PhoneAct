package com.dsh.phoneact.core

import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * 超级小爱通道。
 *
 * 为什么需要它：有些任务用"看屏幕 + 点坐标"做又慢又脆 ——
 * 查天气/汇率、发消息给某人、设提醒、点外卖、控制智能家居、翻译、总结屏幕内容……
 * 超级小爱有系统级权限和云端能力，直接说人话更快更稳。
 *
 * 实测入口（HyperOS）：
 *   am start -a android.intent.action.ASSIST   → 焦点变为 voice_assist_root
 *   → 点底部输入条（框体检测的 kind=input）
 *   → 剪贴板 + KEYCODE_PASTE 输入中文
 *   → 点"发送"
 */
object XiaoAi {

    const val PKG = "com.miui.voiceassist"
    private const val ASSIST_SERVICE = "com.miui.voiceassist/com.xiaomi.voiceassistant.AssistInteractionService"

    data class Outcome(
        val ok: Boolean,
        val stage: String,
        val detail: String,
        val answer: String = "",
        val elements: Int = 0,
    )

    /** 当前是否是小爱/小爱悬浮窗持有焦点。 */
    fun hasFocus(): Boolean {
        if (!RootShell.available) return false
        val d = RootShell.run("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -4", 12000).stdout
        return d.contains("voice_assist_root") || d.contains("voiceassist") || d.contains("voiceassistant")
    }

    fun installed(): Boolean {
        if (!RootShell.available) return false
        val r = RootShell.run("pm list packages " + PKG, 12000)
        return r.stdout.contains(PKG)
    }

    fun version(): String {
        if (!RootShell.available) return ""
        return RootShell.run("dumpsys package " + PKG + " | grep versionName | head -1", 12000)
            .stdout.substringAfter("versionName=", "").trim()
    }

    /** 默认助理是否就是小爱。 */
    fun isDefaultAssistant(): Boolean {
        if (!RootShell.available) return false
        val d = RootShell.run("settings get secure assistant", 12000).stdout
        return d.contains(PKG)
    }

    fun statusJson(): JSONObject = JSONObject()
        .put("package", PKG)
        .put("installed", installed())
        .put("version", version())
        .put("isDefaultAssistant", isDefaultAssistant())
        .put("expectedComponent", ASSIST_SERVICE)
        .put("hasFocus", hasFocus())
        .put("root", RootShell.available)

    // ------------------------------------------------------------------
    // 提速相关的辅助
    // ------------------------------------------------------------------

    @Volatile private var focusCacheAt = 0L
    @Volatile private var focusCacheVal = false

    /** 带缓存的焦点判断。dumpsys 很重，不能反复调。 */
    private fun hasFocusCached(ttlMs: Long = 400): Boolean {
        val now = System.currentTimeMillis()
        if (now - focusCacheAt < ttlMs) return focusCacheVal
        focusCacheVal = hasFocus()
        focusCacheAt = now
        return focusCacheVal
    }

    /** 无障碍事件给出的前台包名是零成本的；悬浮窗场景再退回 dumpsys。 */
    private fun isXiaoAiVisible(): Boolean =
        FrameHub.foregroundPackage == PKG || hasFocusCached()

    private fun pollXiaoAi(ms: Long): Boolean {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < ms) {
            if (isXiaoAiVisible()) return true
            Thread.sleep(120)
        }
        return false
    }

    /**
     * 等到画面稳定（长时间没有帧变化）再返回，替代固定 sleep。
     * 语音会话里画面会从"我在听"跳到卡片，稳定即代表执行完毕。
     */
    private fun waitStable(minMs: Long, maxMs: Long, quietMs: Long = 1100): ScreenModel? {
        val t0 = System.currentTimeMillis()
        var lastHash = FrameHub.model.value?.frameHash ?: 0L
        var lastChange = t0
        while (System.currentTimeMillis() - t0 < maxMs) {
            Thread.sleep(250)
            val m = FrameHub.model.value ?: continue
            if (m.frameHash != lastHash) {
                lastHash = m.frameHash
                lastChange = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - t0 >= minMs &&
                System.currentTimeMillis() - lastChange >= quietMs
            ) {
                return m
            }
        }
        return FrameHub.model.value
    }

    /** 唤起超级小爱。返回是否成功把它带到前台。 */
    fun launch(): Boolean {
        if (!RootShell.available) RootShell.probe()
        if (!RootShell.available) return false
        focusCacheAt = 0L
        if (isXiaoAiVisible()) return true
        RootShell.run("am start -a android.intent.action.ASSIST", 10000)
        if (pollXiaoAi(3000)) return true
        // 有些 ROM 会弹助理选择器，里面会出现"超级小爱悬浮窗"这一项
        runCatching {
            val item = FrameHub.model.value?.elements
                ?.firstOrNull { it.text.contains("超级小爱") && it.text.contains("悬浮窗") }
                ?: FrameHub.model.value?.elements?.firstOrNull { it.text.contains("超级小爱") }
            if (item != null) {
                Actions.tap(item.bounds.centerX, item.bounds.centerY)
            }
        }
        return pollXiaoAi(2500)
    }

    /**
     * 把一条自然语言指令交给超级小爱。
     *
     * @param send     true = 自动点发送并等待回答；false = 只填入输入框
     * @param waitMs   发送后等待回答的时间
     */
    fun ask(instruction: String, send: Boolean, waitMs: Long): Outcome {
        if (instruction.isBlank()) return Outcome(false, "input", "指令为空")
        if (!installed()) return Outcome(false, "check", "未安装超级小爱（$PKG）")
        if (!RootShell.available) RootShell.probe()
        if (!RootShell.available) return Outcome(false, "check", "需要 root 才能唤起小爱")

        // 1) 唤起
        if (!launch()) return Outcome(false, "launch", "唤起超级小爱失败（焦点未切到 voice_assist_root）")

        // 2) 定位底部输入条 —— 用框体检测，避免写死坐标
        val screen = FrameHub.recognizeNow(true)
        val w = screen?.width ?: 1080
        val h = screen?.height ?: 2400
        val bar = screen?.elements
            ?.filter { it.source == ElementSource.FRAME && it.kind == "input" && it.bounds.top > h * 0.70 }
            ?.maxByOrNull { it.bounds.area }
        val bx = bar?.bounds?.centerX ?: (w / 2)
        val by = bar?.bounds?.centerY ?: (h * 93 / 100)
        Actions.tap(bx, by)
        Thread.sleep(1800)

        // 3) 写入指令：root 的 input text 不支持中文，走剪贴板 + 粘贴
        if (!Clipboard.setText(instruction)) return Outcome(false, "clipboard", "写入剪贴板失败")
        Actions.keyEvent(KeyEvent.KEYCODE_PASTE)
        Thread.sleep(1200)

        if (!send) return Outcome(true, "filled", "指令已填入小爱输入框，等待人工确认", instruction)

        // 3.5) 记录发送前的屏幕文本：小爱是悬浮窗，回答区背后还叠着原 App 的内容，
        //      直接截取上半屏会把背景文字混进来。用"发送前/后"文本差集把回答提纯。
        val before = FrameHub.recognizeNow(false)
            ?.elements?.map { it.text.trim() }?.filter { it.isNotEmpty() }?.toSet()
            ?: emptySet()

        // 4) 找发送按钮（位置随面板高度变化，必须动态定位）
        var tapped = false
        if (ActAccessibilityServiceOnline()) {
            tapped = runCatching { UiTree.clickByText("发送", exact = true) }.getOrDefault(false)
        }
        if (!tapped) {
            val m = FrameHub.recognizeNow(true)
            val btn = m?.elements?.firstOrNull { it.text.trim() == "发送" }
            if (btn != null) {
                Actions.tap(btn.bounds.centerX, btn.bounds.centerY)
                tapped = true
            }
        }
        if (!tapped) {
            Actions.keyEvent(KeyEvent.KEYCODE_ENTER)
        }

        // 5) 等回答
        Thread.sleep(waitMs.coerceIn(1500, 60000))

        val after = FrameHub.recognizeNow(true)
        val answer = collectAnswer(after, h, before)
        return Outcome(true, "answered", if (tapped) "已发送" else "已回车发送", answer, after?.elements?.size ?: 0)
    }

    /**
     * 语音注入通道：把指令用 TTS 合成成语音，再 hook AudioRecord 喂给小爱
     * —— 因为小爱对"发消息/打电话"这类能力只认语音。
     *
     * 顺序很重要：必须先合成好 PCM（写入 seq），再唤起小爱，
     * 因为注入是在小爱 startRecording 那一刻拉取的。
     */
    fun askByVoice(instruction: String, waitMs: Long, wavBase64: String? = null): Outcome {
        if (instruction.isBlank()) return Outcome(false, "input", "指令为空")
        if (!installed()) return Outcome(false, "check", "未安装超级小爱")
        if (!RootShell.available) RootShell.probe()
        if (!RootShell.available) return Outcome(false, "check", "需要 root")

        // 优先用外部提供的音频（设备上没有可用 TTS 引擎时的通道），否则走本机 TTS
        val voiced = if (!wavBase64.isNullOrBlank()) {
            XiaoAiVoice.loadWavBase64(wavBase64)
        } else {
            XiaoAiVoice.synthesize(instruction)
        }
        if (!voiced) return Outcome(false, "tts", "语音准备失败: ${XiaoAiVoice.error}")

        // 注入是在 startRecording 那一刻拉取的，所以必须保证小爱是"全新一次录音"。
        // 先杀掉旧进程，避免它复用已经开始的录音会话。
        if (hasFocusCached(0)) {
            RootShell.run("am force-stop " + PKG, 12000)
            Thread.sleep(700)
        }

        // 复用后台识别循环已有的结果当基线，省掉一次完整识别（约 1-3 秒）
        val before = FrameHub.model.value
            ?.elements?.map { it.text.trim() }?.filter { it.isNotEmpty() }?.toSet()
            ?: emptySet()

        if (!launch()) return Outcome(false, "launch", "唤起超级小爱失败")

        // 语音是实时喂的：先等播完，再等画面稳定（执行完成），不再固定 sleep
        val playMs = XiaoAiVoice.durationMs.toLong() + 600
        val after = waitStable(playMs, waitMs.coerceIn(4000, 60000))
        val answer = collectAnswer(after, after?.height ?: 2400, before)
        return Outcome(
            ok = true,
            stage = "voice",
            detail = "已注入语音 #${XiaoAiVoice.seq}（${XiaoAiVoice.durationMs}ms，需 Xposed 生效）",
            answer = answer,
            elements = after?.elements?.size ?: 0,
        )
    }

    // ------------------------------------------------------------------
    // 输入层注入（推荐通道）
    //
    // 语音注入要跟 5 秒录音窗口赛跑，还要 TTS 引擎，链路长。
    // 更直接的做法：文字照常输入（走已验证可用的剪贴板+粘贴），
    // 但把进入小爱决策层的 QueryInfo 改成"像是语音来的"，并替换成我们的指令。
    //   y00.u0 字段: a=dialogId, b=queryText, d=isFromAiInputPage, f=skillId
    // ------------------------------------------------------------------

    @Volatile private var pendingQuery: String = ""
    @Volatile private var pendingSeq: Long = 0L

    fun setPendingQuery(text: String): Long {
        pendingQuery = text
        pendingSeq = System.currentTimeMillis()
        Lg.i("输入层注入已排队: ${text.take(40)} seq=$pendingSeq")
        return pendingSeq
    }

    fun clearPendingQuery() {
        pendingQuery = ""
        pendingSeq = 0L
    }

    /** 想让小爱看到的 query origin（用于把文字输入伪装成语音来源）。 */
    @Volatile private var originOverride: String = ""

    fun setOriginOverride(v: String) { originOverride = v }

    fun pendingJson(): JSONObject = JSONObject()
        .put("seq", pendingSeq)
        .put("text", pendingQuery)
        .put("origin", originOverride)

    /** 用文字输入触发，靠输入层 hook 让它被当成语音指令执行。 */
    fun askWithInputHook(instruction: String, waitMs: Long): Outcome {
        if (instruction.isBlank()) return Outcome(false, "input", "指令为空")
        if (!installed()) return Outcome(false, "check", "未安装超级小爱")
        setPendingQuery(instruction)

        val before = FrameHub.recognizeNow(false)
            ?.elements?.map { it.text.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

        // 复用已验证的文字通道把"任意一句话"送进小爱，具体内容由 hook 在决策层替换
        val trigger = ask(TRIGGER_TEXT, send = true, waitMs = 800)
        if (!trigger.ok) {
            clearPendingQuery()
            return Outcome(false, "trigger", "唤起小爱文字输入失败: ${trigger.detail}")
        }

        Thread.sleep(waitMs.coerceIn(3000, 60000))
        val after = FrameHub.recognizeNow(true)
        val answer = collectAnswer(after, after?.height ?: 2400, before)
        clearPendingQuery()
        return Outcome(
            ok = true, stage = "input_hook",
            detail = "已通过输入层注入（需 Xposed 生效）",
            answer = answer, elements = after?.elements?.size ?: 0,
        )
    }

    /** 触发用的占位文本，真正执行的指令由输入层 hook 替换。 */
    private const val TRIGGER_TEXT = "你好"

    /**
     * 【推荐】把文字指令伪装成语音来源。
     *
     * 实测小爱按 QueryOrigin 做能力门禁：
     *   文字输入框 -> "QueryEditBar"  （微信发消息会被拒："仅支持语音对话方式"）
     *   语音按钮   -> "VoiceButton"   （放行）
     * 所以直接照常打字，但在它写入 origin 时改成 VoiceButton，即可走语音的能力集。
     */
    /**
     * 【最终方案】语音路径触发 + 输入层篡改文本。
     *
     * 为什么这样最好：
     *  - 小爱按 QueryOrigin 做门禁，语音路径天然是 "VoiceButton"，不需要伪造成来源；
     *  - 但让 ASR 完整听懂一整句中文（还要塞进 ~5 秒录音窗口）既慢又不可靠；
     *  - 所以只喂一句约 1 秒的触发词让 ASR 出个结果，再由输入层 hook
     *    把识别文本替换成真正的指令。既拿到语音身份，又保证指令准确。
     */
    fun askVoiceTamper(instruction: String, waitMs: Long): Outcome {
        if (instruction.isBlank()) return Outcome(false, "input", "指令为空")
        if (!installed()) return Outcome(false, "check", "未安装超级小爱")
        if (!RootShell.available) RootShell.probe()
        if (!RootShell.available) return Outcome(false, "check", "需要 root")

        // 1) 准备触发词音频（喂给麦克风）与待篡改指令（交给输入层 hook）
        if (!XiaoAiVoice.loadTriggerBlip()) {
            return Outcome(false, "blip", "触发词音频载入失败: ${XiaoAiVoice.error}")
        }
        setPendingQuery(instruction)

        val before = FrameHub.model.value
            ?.elements?.map { it.text.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

        // 2) 全新一次录音会话（注入发生在 startRecording）。只在必要时才杀进程。
        if (hasFocusCached(0)) {
            RootShell.run("am force-stop " + PKG, 12000)
            Thread.sleep(700)
        }
        if (!launch()) {
            clearPendingQuery()
            return Outcome(false, "launch", "唤起超级小爱失败")
        }

        // 3) 先等触发词播完，再等画面稳定（ASR + 篡改 + 执行完成）
        val playMs = XiaoAiVoice.durationMs.toLong() + 800
        val after = waitStable(playMs, waitMs.coerceIn(4000, 60000))
        val answer = collectAnswer(after, after?.height ?: 2400, before)
        clearPendingQuery()
        return Outcome(
            ok = true, stage = "voice_tamper",
            detail = "已走语音路径（VoiceButton）+ 篡改识别文本为「" + instruction.take(30) + "」",
            answer = answer, elements = after?.elements?.size ?: 0,
        )
    }

    fun askAsVoice(instruction: String, waitMs: Long, origin: String = "VoiceButton"): Outcome {
        if (instruction.isBlank()) return Outcome(false, "input", "指令为空")
        if (!installed()) return Outcome(false, "check", "未安装超级小爱")
        if (!RootShell.available) RootShell.probe()
        if (!RootShell.available) return Outcome(false, "check", "需要 root")

        setOriginOverride(origin)
        val before = FrameHub.recognizeNow(false)
            ?.elements?.map { it.text.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
        return try {
            val t = ask(instruction, send = true, waitMs = waitMs)
            if (!t.ok) return Outcome(false, "trigger", t.detail)
            val after = waitStable(1200, waitMs.coerceIn(4000, 60000))
            val answer = collectAnswer(after, after?.height ?: 2400, before)
            Outcome(
                ok = true, stage = "origin_spoof",
                detail = "指令已发送，QueryOrigin 伪造成 [" + origin + "]（需 Xposed 生效）",
                answer = answer, elements = after?.elements?.size ?: 0,
            )
        } finally {
            setOriginOverride("")
        }
    }

    /** 读取 LSPosed 日志里与语音注入相关的行，用于诊断 hook 是否真的跑起来了。 */
    fun injectLog(lines: Int = 40): String {
        if (!RootShell.available) RootShell.probe()
        if (!RootShell.available) return "需要 root"
        val cmd = "grep -aE 'PhoneAct.*(AudioRecord|已载入语音|注入|startRecording)' " +
            "/data/adb/lspd/log/modules_*.log 2>/dev/null | tail -n " + lines.coerceIn(5, 200)
        val r = RootShell.run(cmd, 20000)
        return r.stdout.ifBlank { "(日志里没有语音注入相关记录：说明小爱进程没被注入，或它的录音不走 Java AudioRecord)" }
    }

    private fun ActAccessibilityServiceOnline(): Boolean =
        com.dsh.phoneact.service.ActAccessibilityService.isConnected

    /**
     * 取回答文字：上半屏 + 只保留"发送后新出现"的文本，按位置从上到下拼接。
     * 这样能把悬浮窗背后原 App 的内容（微信聊天记录、桌面图标名等）过滤掉。
     */
    private fun collectAnswer(model: ScreenModel?, screenH: Int, before: Set<String>): String {
        model ?: return ""
        val seen = HashSet<String>()
        return model.elements
            .filter { it.text.isNotBlank() && it.bounds.centerY < screenH * 0.66 && it.bounds.centerY > screenH * 0.04 }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            .map { it.text.trim() }
            .filter { it.length > 1 && it !in before && seen.add(it) }
            .joinToString(" ")
            .take(1500)
    }

    fun outcomeJson(o: Outcome): JSONObject = JSONObject().apply {
        put("ok", o.ok)
        put("stage", o.stage)
        put("detail", o.detail)
        if (o.answer.isNotEmpty()) put("answer", o.answer)
        put("elements", o.elements)
    }
}
