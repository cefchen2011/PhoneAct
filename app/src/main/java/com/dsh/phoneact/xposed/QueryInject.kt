package com.dsh.phoneact.xposed

import android.content.ContentResolver
import android.net.Uri
import org.json.JSONObject

/**
 * 输入层注入。
 *
 * 为什么比语音注入更好：语音注入要跟小爱的 ~5 秒录音窗口赛跑，还依赖 TTS 引擎；
 * 而小爱真正做决策的地方是它拿到的 QueryInfo。把 QueryInfo 换成"像是语音说的 + 我们的指令"，
 * 就能直接进入它的执行链路。
 *
 * y00.u0 结构（Kotlin data class，字段名被混淆）：
 *   a = dialogId (String)
 *   b = queryText (String)          <-- 要替换
 *   c = queryExtraInfo (y00.t0)
 *   d = isFromAiInputPage (boolean) <-- 文字输入为 true，语音为 false
 *   e = isRenewSession (Boolean)
 *   f = skillId (String)
 */
object QueryInject {

    private const val AUTHORITY = "com.dsh.phoneact.xiaoi"

    /** 当前进程的包名，由 PhoneActModule 写入。 */
    @Volatile var processPackage: String = ""

    @Volatile private var lastSeq = 0L

    @Volatile var lastInfo: String = "尚未触发"
        private set

    private val hookCounter = java.util.concurrent.atomic.AtomicLong(0)
    val hookCount: Long get() = hookCounter.get()
    fun bumpHookCount(): Long = hookCounter.incrementAndGet()

    /** 读取宿主排队的指令；没有则返回 null。 */
    fun takePending(): String? {
        val ctx = HookContext.app() ?: return null
        val res: ContentResolver = ctx.contentResolver
        val bundle = runCatching {
            res.call(Uri.parse("content://$AUTHORITY/meta"), "meta", null, null)
        }.getOrNull() ?: return null
        val raw = bundle.getString("query") ?: return null
        val o = JSONObject(raw)
        // 顺带同步宿主设定的 origin 覆盖值
        val wantOrigin = o.optString("origin", "")
        originOverride = wantOrigin.ifBlank { null }
        val seq = o.optLong("seq", 0L)
        val text = o.optString("text", "")
        if (seq <= 0L || text.isBlank()) return null
        // 注意：这里**不能**只返回一次。
        // 实测小爱会把同一句话写进 setQueryText 多次（实测 3~5 次），
        // 若只替换第一次，后续调用会用原始识别文本把我们的指令覆盖回去。
        // 因此只要宿主还挂着这条指令（seq > 0）就持续替换，由宿主在结束时清零。
        if (seq != lastSeq) {
            lastSeq = seq
            log("待注入指令已激活: " + text.take(40))
        }
        return text
    }

    /**
     * 唤起来源的全串覆盖值。
     *
     * 真人基准日志：
     *   VA_InstructionProcessManager: queryFrom=com.miui.voiceassist.ACTION_VOICE_START_VOICEASSIST
     *       &&android.intent.action.ASSIST&&double_click_fullscreen_gesture_line
     * 而程序化双击手势条只能得到
     *   android.intent.action.ASSIST&&double_click_fullscreen_gesture_line
     * —— 少掉 ACTION_VOICE_START_VOICEASSIST 前缀，微信技能就不交棒。
     * 这里在 getter 上把它补全。
     */
    const val FULL_WAKE_ORIGIN =
        "com.miui.voiceassist.ACTION_VOICE_START_VOICEASSIST" +
            "&&android.intent.action.ASSIST&&double_click_fullscreen_gesture_line"

    @Volatile var wakeOriginOverride: String? = null

    /** 需要把 query origin 覆盖成的值（null 表示不覆盖）。 */
    @Volatile private var originOverride: String? = null

    @Volatile private var lastFetch = 0L

    /**
     * 直接向宿主查询覆盖值。
     *
     * 不能只依赖 takePending() 顺带刷新：实测 setQueryText 与 setQueryOrigin
     * 几乎在同一毫秒发生，顺序不确定，等不到刷新就漏掉了。
     */
    fun takeOriginOverride(): String? {
        val now = System.currentTimeMillis()
        if (now - lastFetch > 150) {
            lastFetch = now
            runCatching { refreshFromHost() }
        }
        return originOverride
    }

    private fun refreshFromHost() {
        val ctx = HookContext.app() ?: return
        val bundle = runCatching {
            ctx.contentResolver.call(Uri.parse("content://$AUTHORITY/meta"), "meta", null, null)
        }.getOrNull() ?: return
        val raw = bundle.getString("query") ?: return
        val o = JSONObject(raw)
        val want = o.optString("origin", "")
        val next = want.ifBlank { null }
        if (next != originOverride) {
            originOverride = next
            log("QueryOrigin 覆盖目标刷新为: $next")
        }
        val wake = o.optString("wake_origin", "")
        wakeOriginOverride = if (wake == "full") FULL_WAKE_ORIGIN else wake.ifBlank { null }
    }

    fun setOriginOverride(v: String?) {
        originOverride = v
    }

    fun note(msg: String) {
        lastInfo = msg
    }

    fun log(msg: String) = Bridge.log(msg, "query")
}
