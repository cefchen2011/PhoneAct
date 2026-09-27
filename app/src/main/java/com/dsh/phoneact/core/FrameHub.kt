package com.dsh.phoneact.core

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityEvent
import com.dsh.phoneact.service.ActAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 屏幕更新中枢。
 *
 * "每次屏幕内容更新都识别一次" 的两条触发源：
 *   A. 无障碍事件（窗口内容变化/滚动/焦点变化…）→ 立即置脏
 *   B. 视觉帧差分（32x32 灰度平均绝对差）→ 兜底覆盖无障碍事件缺失的场景（视频、游戏、Canvas 绘制）
 * 两者任一命中即触发一次完整识别，并用 debounce 合并抖动。
 */
object FrameHub {

    private const val CHANGE_THRESHOLD = 2.5   // 灰度平均绝对差阈值(0..255)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null

    private val _model = MutableStateFlow<ScreenModel?>(null)
    val model: StateFlow<ScreenModel?> = _model.asStateFlow()

    private val _preview = MutableStateFlow<Bitmap?>(null)
    val preview: StateFlow<Bitmap?> = _preview.asStateFlow()

    private val _updates = MutableSharedFlow<ScreenModel>(replay = 1, extraBufferCapacity = 32)
    val updates: SharedFlow<ScreenModel> = _updates.asSharedFlow()

    @Volatile var foregroundPackage: String = ""
        private set

    @Volatile var foregroundActivity: String = ""
        private set

    @Volatile var running: Boolean = false
        private set

    @Volatile var framesSeen: Long = 0
        private set

    @Volatile var lastError: String = ""
        private set

    @Volatile var xposedSignals: Long = 0
        private set

    private val dirty = AtomicBoolean(true)
    private var lastFp: Capture.Fingerprint? = null
    private var seq = 0L
    private var lastRecognizeAt = 0L
    private var lastPreviewAt = 0L

    fun onAccessibilityEvent(e: AccessibilityEvent) {
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val p = e.packageName?.toString().orEmpty()
                if (p.isNotBlank()) foregroundPackage = p
                val cls = e.className?.toString().orEmpty()
                if (cls.isNotBlank() && !cls.startsWith("android.widget.")) foregroundActivity = cls
                dirty.set(true)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> dirty.set(true)
            else -> Unit
        }
    }

    fun markDirty() {
        dirty.set(true)
    }

    /** 由 Xposed 注入的进程上报（Activity#onResume）。 */
    fun onXposedForeground(pkg: String, activity: String) {
        if (pkg.isNotBlank()) foregroundPackage = pkg
        if (activity.isNotBlank()) foregroundActivity = activity
        dirty.set(true)
    }

    /** 由 Xposed 注入的进程上报（ViewRootImpl#performTraversals 节流信号）。 */
    fun onXposedContentChange(pkg: String) {
        xposedSignals++
        dirty.set(true)
    }

    fun start() {
        if (running) return
        running = true
        dirty.set(true)
        lastFp = null
        loopJob = scope.launch {
            Lg.i("FrameHub 识别循环启动")
            while (isActive && running) {
                val s = Prefs.current
                if (!s.recognizeOnChange) {
                    delay(600)
                    continue
                }
                try {
                    step(s)
                } catch (t: Throwable) {
                    lastError = "识别循环异常: ${t.message}"
                    Lg.e("识别循环异常", t)
                    delay(1200)
                }
            }
            Lg.i("FrameHub 识别循环退出")
        }
    }

    fun stop() {
        running = false
        loopJob?.cancel()
        loopJob = null
    }

    private fun step(s: Settings) {
        val cap = Capture.capture()
        if (cap == null) {
            lastError = "截屏失败（无障碍/root/MediaProjection 均不可用）"
            Thread.sleep(1200)
            return
        }
        framesSeen++
        val fp = Capture.fingerprint(cap.bitmap)
        val prev = lastFp
        val d = if (prev == null) 999.0 else Capture.diff(prev, fp)
        val wasDirty = dirty.getAndSet(false)
        val changed = wasDirty || d > CHANGE_THRESHOLD

        if (!changed) {
            // 未变化：仅在超过 1.5s 时刷新一次预览，避免无谓的位图分配
            val now = System.currentTimeMillis()
            if (now - lastPreviewAt > 1500) {
                lastPreviewAt = now
                publishPreview(cap.bitmap)
            }
            cap.bitmap.recycleQuietly()
            Thread.sleep(100)
            return
        }

        lastFp = fp
        seq++
        val t0 = System.currentTimeMillis()
        val model = Recognizer.recognize(
            bmp = cap.bitmap,
            ctxInfo = Recognizer.ScreenContext(foregroundPackage, foregroundActivity),
            settings = s,
            frameHash = fp.hash,
            changed = true,
            seq = seq,
            captureBackend = cap.backend,
        )
        _model.value = model
        lastPreviewAt = System.currentTimeMillis()
        publishPreview(cap.bitmap)
        cap.bitmap.recycleQuietly()
        lastRecognizeAt = System.currentTimeMillis()
        Lg.i("识别 #$seq 块=${model.blocks.size} 元素=${model.elements.size} 耗时=${model.totalMs}ms 截屏=${cap.ms}ms/${cap.backend} 总=${System.currentTimeMillis() - t0}ms")
        scope.launch { _updates.emit(model) }
        val wait = s.debounceMs - (System.currentTimeMillis() - lastRecognizeAt)
        if (wait > 0) Thread.sleep(wait)
    }

    private fun publishPreview(src: Bitmap): Bitmap {
        val maxW = 480
        val scale = if (src.width > maxW) maxW.toFloat() / src.width else 1f
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        _preview.value?.recycleQuietly()
        return small
    }

    /** 主动识别一次；force=true 时忽略变化检测。 */
    fun recognizeNow(force: Boolean = true): ScreenModel? {
        val s = Prefs.current
        val cap = Capture.capture() ?: run {
            lastError = "截屏失败"
            return null
        }
        val fp = Capture.fingerprint(cap.bitmap)
        val prev = lastFp
        val changed = force || prev == null || Capture.diff(prev, fp) > CHANGE_THRESHOLD
        lastFp = fp
        seq++
        val model = Recognizer.recognize(
            bmp = cap.bitmap,
            ctxInfo = Recognizer.ScreenContext(foregroundPackage, foregroundActivity),
            settings = s,
            frameHash = fp.hash,
            changed = changed,
            seq = seq,
            captureBackend = cap.backend,
        )
        _model.value = model
        lastPreviewAt = System.currentTimeMillis()
        publishPreview(cap.bitmap)
        cap.bitmap.recycleQuietly()
        scope.launch { _updates.emit(model) }
        return model
    }

    /** 等待下一次屏幕更新（供 MCP 的长轮询/等待类工具使用）。 */
    suspend fun awaitUpdate(sinceSeq: Long, timeoutMs: Long): ScreenModel? {
        val cur = _model.value
        if (cur != null && cur.seq > sinceSeq) return cur
        return withTimeoutOrNull(timeoutMs.coerceIn(100, 120_000)) {
            _updates.first { it.seq > sinceSeq }
        }
    }

    fun lastSeq(): Long = _model.value?.seq ?: 0L

    fun statusJson(): org.json.JSONObject = org.json.JSONObject()
        .put("running", running)
        .put("framesSeen", framesSeen)
        .put("seq", seq)
        .put("foregroundPackage", foregroundPackage)
        .put("foregroundActivity", foregroundActivity)
        .put("lastError", lastError)
        .put("lastRecognizeAt", lastRecognizeAt)
        .put("xposedSignals", xposedSignals)
        .put("accessibilityConnected", ActAccessibilityService.isConnected)

    private fun Bitmap?.recycleQuietly() {
        runCatching { if (this != null && !isRecycled) recycle() }
    }
}
