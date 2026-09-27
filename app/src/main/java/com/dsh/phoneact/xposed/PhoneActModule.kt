package com.dsh.phoneact.xposed

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * PhoneAct 的 Xposed / LSPosed 侧。
 *
 * 职责（都是纯 Java 层可稳定完成的增强，缺失时宿主会自动降级到无障碍 + root）：
 *   1. 让宿主进程知道自己确实跑在 Xposed 环境里（同进程静态字段直写）。
 *   2. 精确前台应用/Activity 追踪：hook 所有作用域内进程的 Activity#onResume。
 *   3. 高精度"屏幕内容更新"信号：hook ViewRootImpl#performTraversals（节流），
 *      比无障碍事件更细，能覆盖视频/游戏/Canvas 自绘等无障碍盲区。
 *   4. SystemUI 免手动确认：MediaProjection 授权弹窗出现后自动点确认，
 *      使 MediaProjection 截屏通道可无人值守。
 *
 * 所有 hook 均包在 try/catch 中：任一变体失败不影响宿主其他能力。
 */
class PhoneActModule : IXposedHookLoadPackage, IXposedHookZygoteInit {

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        bridgeVersion = runCatching { XposedBridge.getXposedVersion() }.getOrDefault(0)
        installed.add("initZygote")
        log("initZygote path=${startupParam?.modulePath} systemServer=${startupParam?.startsSystemServer} bridge=$bridgeVersion")
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        val lp = lpparam ?: return
        try {
            if (bridgeVersion == 0) bridgeVersion = runCatching { XposedBridge.getXposedVersion() }.getOrDefault(0)
            if (lp.isFirstApplication) {
                hookForegroundTracking(lp)
                hookContentUpdates(lp)
                if (lp.packageName == "com.android.systemui") hookMediaProjectionConsent(lp)
            }
        } catch (t: Throwable) {
            lastError = "${lp.packageName}: ${t.message}"
            log("handleLoadPackage 失败 ${lp.packageName}: $t")
        } finally {
            // 模块与宿主不共享 ClassLoader，状态必须经广播回传
            reportAlive(lp)
        }
    }

    // ------------------------------------------------------------------
    // 2. 前台 Activity 追踪
    // ------------------------------------------------------------------

    private fun hookForegroundTracking(lp: XC_LoadPackage.LoadPackageParam) {
        val cls = XposedHelpers.findClassIfExists("android.app.Activity", lp.classLoader) ?: return
        XposedHelpers.findAndHookMethod(cls, "onResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                runCatching {
                    val act = param.thisObject as? Activity ?: return
                    val pkg = act.packageName ?: return
                    val name = act.javaClass.name
                    report(lp.packageName, EVENT_FOREGROUND, mapOf(
                        "package" to pkg,
                        "activity" to name,
                        "pid" to android.os.Process.myPid(),
                        "ts" to System.currentTimeMillis(),
                    ))
                }.onFailure { log("onResume hook 异常: $it") }
            }
        })
        installed.add("Activity#onResume(${lp.packageName})")
    }

    // ------------------------------------------------------------------
    // 3. 屏幕内容更新信号
    // ------------------------------------------------------------------

    private fun hookContentUpdates(lp: XC_LoadPackage.LoadPackageParam) {
        val cls = XposedHelpers.findClassIfExists("android.view.ViewRootImpl", lp.classLoader) ?: return
        XposedHelpers.findAndHookMethod(cls, "performTraversals", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val now = System.currentTimeMillis()
                if (now - lastReport < CONTENT_REPORT_INTERVAL_MS) return
                lastReport = now
                runCatching {
                    report(lp.packageName, EVENT_CONTENT, mapOf(
                        "package" to lp.packageName,
                        "ts" to now,
                    ))
                }
            }
        })
        installed.add("ViewRootImpl#performTraversals(${lp.packageName})")
    }

    // ------------------------------------------------------------------
    // 4. MediaProjection 授权弹窗自动确认
    // ------------------------------------------------------------------

    private fun hookMediaProjectionConsent(lp: XC_LoadPackage.LoadPackageParam) {
        val candidates = listOf(
            "com.android.systemui.mediaprojection.MediaProjectionPermissionActivity",
            "com.android.systemui.media.MediaProjectionPermissionActivity",
            "com.android.systemui.media.MediaProjectionPermissionActivity$1",
        )
        var hooked = false
        for (name in candidates) {
            val cls = XposedHelpers.findClassIfExists(name, lp.classLoader) ?: continue
            XposedHelpers.findAndHookMethod(cls, "onCreate", Bundle::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val act = param.thisObject as? Activity ?: return
                    if (act.javaClass.name.contains("\$")) return
                    scheduleAutoConfirm(act)
                }
            })
            installed.add("MediaProjection 自动授权($name)")
            hooked = true
            break
        }
        if (!hooked) log("未找到 MediaProjectionPermissionActivity，自动授权不可用")
    }

    private fun scheduleAutoConfirm(act: Activity) {
        val handler = Handler(Looper.getMainLooper())
        var attempts = 0
        val runnable = object : Runnable {
            override fun run() {
                attempts++
                val clicked = runCatching { findAndClickConfirm(act) }.getOrDefault(false)
                if (clicked) {
                    log("已自动确认 MediaProjection 授权弹窗")
                    return
                }
                if (attempts < 12) handler.postDelayed(this, 250)
            }
        }
        handler.postDelayed(runnable, 300)
    }

    private fun findAndClickConfirm(act: Activity): Boolean {
        val root = act.window?.decorView as? ViewGroup ?: return false
        val target = findButton(root, 0) ?: return false
        return target.performClick()
    }

    private fun findButton(v: View, depth: Int): View? {
        if (depth > 14) return null
        if (v is TextView) {
            val t = v.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty() && CONFIRM_TEXTS.any { t.equals(it, true) || t.contains(it) }) {
                if (v.isClickable && v.isEnabled) return v
                var p = v.parent
                while (p is View) {
                    if (p.isClickable && p.isEnabled) return p
                    p = p.parent
                }
                if (v.isEnabled) return v
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                findButton(v.getChildAt(i), depth + 1)?.let { return it }
            }
        }
        return null
    }

    // ------------------------------------------------------------------

    private fun report(sourcePackage: String, type: String, data: Map<String, Any>) {
        val app: Application = currentApplication() ?: return
        val i = Intent(ACTION_XPOSED_EVENT)
        i.setPackage(HOST_PACKAGE)
        // 统一加 pa_ 前缀：MIUI 的 contentcatcher 等系统组件会覆写 "package"/"type" 这类通用键
        i.putExtra(EXTRA_TYPE, type)
        i.putExtra(EXTRA_SOURCE, sourcePackage)
        for ((k, v) in data) when (v) {
            is String -> i.putExtra("pa_$k", v)
            is Int -> i.putExtra("pa_$k", v)
            is Long -> i.putExtra("pa_$k", v)
            is Boolean -> i.putExtra("pa_$k", v)
            else -> i.putExtra("pa_$k", v.toString())
        }
        app.sendBroadcast(i)
    }

    private var bridgeVersion = 0
    private var lastError = ""
    private val installed = mutableListOf<String>()

    private var appRef: Application? = null

    private fun currentApplication(): Application? {
        appRef?.let { return it }
        val a = runCatching {
            XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null),
                "currentApplication",
            ) as? Application
        }.getOrNull()
        appRef = a
        return a
    }

    private fun reportAlive(lp: XC_LoadPackage.LoadPackageParam) {
        report(lp.packageName, EVENT_ALIVE, mapOf(
            "package" to lp.packageName,
            "process" to (lp.processName ?: lp.packageName),
            "bridge" to bridgeVersion,
            "hooks" to installed.joinToString(","),
            "error" to lastError,
            "ts" to System.currentTimeMillis(),
        ))
    }

    private fun log(msg: String) {
        runCatching { XposedBridge.log("[PhoneAct] $msg") }
    }

    companion object {
        private const val HOST_PACKAGE = "com.dsh.phoneact"
        private const val ACTION_XPOSED_EVENT = "com.dsh.phoneact.XPOSED_EVENT"
        private const val EVENT_FOREGROUND = "foreground"
        private const val EVENT_CONTENT = "content"
        private const val EVENT_ALIVE = "alive"
        private const val EXTRA_TYPE = "pa_type"
        private const val EXTRA_SOURCE = "pa_source"
        private const val CONTENT_REPORT_INTERVAL_MS = 300L

        private val CONFIRM_TEXTS = listOf(
            "立即开始", "开始录制", "开始", "允许", "确定", "确认",
            "Start now", "Start recording", "Start", "Allow", "OK", "Cast",
        )

        @Volatile private var lastReport = 0L
    }
}
