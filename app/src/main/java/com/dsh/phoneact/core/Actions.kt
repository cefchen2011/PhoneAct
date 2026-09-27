package com.dsh.phoneact.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.dsh.phoneact.service.ActAccessibilityService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class ActionResult(val ok: Boolean, val backend: String, val detail: String = "") {
    fun toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("ok", ok).put("backend", backend).put("detail", detail)
}

/**
 * 操作通道：无障碍手势优先，Magisk su + input 兜底。
 * 两者都不可用时明确报错，不静默失败。
 */
object Actions {

    private fun svc(): AccessibilityService? = ActAccessibilityService.inst

    private fun useA11y(): Boolean = when (Prefs.current.actionBackend) {
        ActionBackend.ACCESSIBILITY -> true
        ActionBackend.ROOT -> false
        ActionBackend.AUTO -> svc() != null
    }

    private fun gesture(path: Path, durationMs: Long, timeoutMs: Long = 3000): ActionResult {
        val s = svc() ?: return ActionResult(false, "a11y", "无障碍服务未连接")
        val latch = CountDownLatch(1)
        var ok = false
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1))
        val desc = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = runCatching {
            s.dispatchGesture(desc, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; latch.countDown() }
                override fun onCancelled(gestureDescription: GestureDescription?) { ok = false; latch.countDown() }
            }, null)
        }.getOrDefault(false)
        if (!dispatched) return ActionResult(false, "a11y", "dispatchGesture 被拒绝")
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return ActionResult(ok, "a11y", if (ok) "" else "手势被取消")
    }

    fun tap(x: Int, y: Int, durationMs: Long = 60): ActionResult {
        if (useA11y()) {
            val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val r = gesture(p, durationMs.coerceAtLeast(40))
            if (r.ok) return r
            Lg.w("无障碍点击失败，回退 root: ${r.detail}")
        }
        if (!RootShell.available) RootShell.probe()
        if (!RootShell.available) return ActionResult(false, "none", "无障碍与 root 均不可用")
        val r = RootShell.tap(x, y)
        return ActionResult(r.ok, "root", r.stderr.take(200))
    }

    fun longPress(x: Int, y: Int, ms: Long = 600): ActionResult {
        if (useA11y()) {
            val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val r = gesture(p, ms)
            if (r.ok) return r
        }
        if (!RootShell.available) RootShell.probe()
        if (!RootShell.available) return ActionResult(false, "none", "无障碍与 root 均不可用")
        val r = RootShell.longPress(x, y, ms)
        return ActionResult(r.ok, "root", r.stderr.take(200))
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, ms: Long = 300): ActionResult {
        if (useA11y()) {
            val p = Path().apply {
                moveTo(x1.toFloat(), y1.toFloat())
                lineTo(x2.toFloat(), y2.toFloat())
            }
            val r = gesture(p, ms)
            if (r.ok) return r
        }
        if (!RootShell.available) RootShell.probe()
        if (!RootShell.available) return ActionResult(false, "none", "无障碍与 root 均不可用")
        val r = RootShell.swipe(x1, y1, x2, y2, ms)
        return ActionResult(r.ok, "root", r.stderr.take(200))
    }

    /** 在弧线上滑动（解锁图案等）。points 为 [[x,y],...]。 */
    fun path(points: List<Pair<Int, Int>>, ms: Long = 400): ActionResult {
        if (points.size < 2) return ActionResult(false, "none", "路径点不足")
        if (useA11y()) {
            val p = Path().apply {
                moveTo(points[0].first.toFloat(), points[0].second.toFloat())
                for (i in 1 until points.size) lineTo(points[i].first.toFloat(), points[i].second.toFloat())
            }
            val r = gesture(p, ms)
            if (r.ok) return r
        }
        var last = ActionResult(false, "none", "无可用通道")
        for (i in 0 until points.size - 1) {
            last = swipe(points[i].first, points[i].second, points[i + 1].first, points[i + 1].second, ms / (points.size - 1))
        }
        return last
    }

    fun setText(text: String, node: AccessibilityNodeInfo? = null): ActionResult {
        val target = node ?: UiTree.focusedEditable()
        if (target != null) {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val ok = runCatching { target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
            if (ok) return ActionResult(true, "a11y", "")
        }
        if (!RootShell.available) RootShell.probe()
        if (!RootShell.available) return ActionResult(false, "none", "无可编辑节点且 root 不可用")
        val r = RootShell.inputText(text)
        return ActionResult(r.ok, "root", r.stderr.take(200))
    }

    fun keyEvent(code: Int): ActionResult {
        if (!RootShell.available) RootShell.probe()
        if (!RootShell.available) return ActionResult(false, "none", "root 不可用")
        val r = RootShell.keyEvent(code)
        return ActionResult(r.ok, "root", r.stderr.take(200))
    }

    fun keyName(name: String): ActionResult {
        val code = KEY_NAMES[name.uppercase()] ?: return ActionResult(false, "none", "未知按键 $name")
        return keyEvent(code)
    }

    fun globalAction(name: String): ActionResult {
        val s = svc() ?: return ActionResult(false, "a11y", "无障碍服务未连接")
        val action = when (name.lowercase()) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents", "recent" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications", "notification" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings", "quicksettings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "power_dialog" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "lock_screen", "lock" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN else -1
            "take_screenshot" -> if (Build.VERSION.SDK_INT >= 28) AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT else -1
            else -> -1
        }
        if (action < 0) return ActionResult(false, "a11y", "不支持的全局动作 $name")
        val ok = runCatching { s.performGlobalAction(action) }.getOrDefault(false)
        return ActionResult(ok, "a11y", "")
    }

    fun back(): ActionResult = globalAction("back").let { if (it.ok) it else keyEvent(KeyEvent.KEYCODE_BACK) }
    fun home(): ActionResult = globalAction("home").let { if (it.ok) it else keyEvent(KeyEvent.KEYCODE_HOME) }
    fun recents(): ActionResult = globalAction("recents").let { if (it.ok) it else keyEvent(KeyEvent.KEYCODE_APP_SWITCH) }

    val KEY_NAMES: Map<String, Int> = mapOf(
        "HOME" to KeyEvent.KEYCODE_HOME,
        "BACK" to KeyEvent.KEYCODE_BACK,
        "MENU" to KeyEvent.KEYCODE_MENU,
        "APP_SWITCH" to KeyEvent.KEYCODE_APP_SWITCH,
        "ENTER" to KeyEvent.KEYCODE_ENTER,
        "DEL" to KeyEvent.KEYCODE_DEL,
        "DELETE" to KeyEvent.KEYCODE_FORWARD_DEL,
        "TAB" to KeyEvent.KEYCODE_TAB,
        "ESCAPE" to KeyEvent.KEYCODE_ESCAPE,
        "SPACE" to KeyEvent.KEYCODE_SPACE,
        "POWER" to KeyEvent.KEYCODE_POWER,
        "VOLUME_UP" to KeyEvent.KEYCODE_VOLUME_UP,
        "VOLUME_DOWN" to KeyEvent.KEYCODE_VOLUME_DOWN,
        "VOLUME_MUTE" to KeyEvent.KEYCODE_VOLUME_MUTE,
        "CAMERA" to KeyEvent.KEYCODE_CAMERA,
        "WAKEUP" to KeyEvent.KEYCODE_WAKEUP,
        "SLEEP" to KeyEvent.KEYCODE_SLEEP,
        "DPAD_UP" to KeyEvent.KEYCODE_DPAD_UP,
        "DPAD_DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
        "DPAD_LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
        "DPAD_RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "DPAD_CENTER" to KeyEvent.KEYCODE_DPAD_CENTER,
        "PASTE" to KeyEvent.KEYCODE_PASTE,
        "COPY" to KeyEvent.KEYCODE_COPY,
        "CUT" to KeyEvent.KEYCODE_CUT,
        "MOVE_HOME" to KeyEvent.KEYCODE_MOVE_HOME,
        "MOVE_END" to KeyEvent.KEYCODE_MOVE_END,
    )
}
