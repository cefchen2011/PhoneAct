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
                // 超级小爱只认语音指令；在框架层替换它的麦克风数据，让它"听到"我们合成的话
                if (lp.packageName == XIAOI_PACKAGE) {
                    hookAudioInject(lp)
                    hookQueryInput(lp)
                }
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
    // 4. 语音注入：替换 AudioRecord 读到的麦克风数据
    // ------------------------------------------------------------------

    private fun hookAudioInject(lp: XC_LoadPackage.LoadPackageParam) {
        val cls = XposedHelpers.findClassIfExists("android.media.AudioRecord", lp.classLoader) ?: return
        XposedHelpers.findAndHookMethod(cls, "startRecording", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                runCatching {
                    val rec = param.thisObject as? android.media.AudioRecord
                    val rate = runCatching { rec?.sampleRate }.getOrNull()
                    val ch = runCatching { rec?.channelCount }.getOrNull()
                    val fmt = runCatching { rec?.audioFormat }.getOrNull()
                    val enc = when (fmt) {
                        android.media.AudioFormat.ENCODING_PCM_8BIT -> "8bit"
                        android.media.AudioFormat.ENCODING_PCM_16BIT -> "16bit"
                        android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24bit"
                        android.media.AudioFormat.ENCODING_PCM_32BIT -> "32bit"
                        android.media.AudioFormat.ENCODING_PCM_FLOAT -> "float32"
                        else -> "?"
                    }
                    val src = runCatching { rec?.audioSource }.getOrNull()
                    log("AudioRecord.startRecording rate=$rate ch=$ch encoding=$enc($fmt) source=$src")
                    // 第一次调用时打印调用栈，确认这个录音实例到底是谁在用
                    if (traceCount < 3) {
                        traceCount++
                        log("startRecording 调用栈:\n" + android.util.Log.getStackTraceString(Throwable()))
                    }
                    VoiceInject.onStartRecording(rec, fmt ?: android.media.AudioFormat.ENCODING_PCM_16BIT)
                }.onFailure { log("startRecording hook 异常 $it") }
            }
        })
        XposedHelpers.findAndHookMethod(cls, "stop", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                runCatching { VoiceInject.onStop() }
            }
        })

        // byte[]
        runCatching {
            XposedHelpers.findAndHookMethod(
                cls, "read", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val n = VoiceInject.readCalls.incrementAndGet()
                        val buf = param.args[0] as? ByteArray ?: return
                        val off = (param.args[1] as? Int) ?: 0
                        val len = (param.args[2] as? Int) ?: return
                        val ok = VoiceInject.fill(buf, off, len)
                        if (ok) param.result = len
                        if (n <= 3 || n % 200 == 0L) {
                            log("read(byte[]) #$n len=$len realResult=${param.result} injected=$ok ${VoiceInject.lastInfo}")
                        }
                    }
                },
            )
        }.onFailure { log("hook read(byte[]) 失败 $it") }

        // short[]
        runCatching {
            XposedHelpers.findAndHookMethod(
                cls, "read", ShortArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val n = VoiceInject.readCalls.incrementAndGet()
                        val buf = param.args[0] as? ShortArray ?: return
                        val off = (param.args[1] as? Int) ?: 0
                        val len = (param.args[2] as? Int) ?: return
                        val ok = VoiceInject.fillShorts(buf, off, len)
                        if (ok) param.result = len
                        if (n <= 3 || n % 200 == 0L) {
                            log("read(short[]) #$n len=$len realResult=${param.result} injected=$ok ${VoiceInject.lastInfo}")
                        }
                    }
                },
            )
        }.onFailure { log("hook read(short[]) 失败 $it") }

        // ByteBuffer 版本：先只统计，确认是否被使用
        runCatching {
            XposedHelpers.findAndHookMethod(
                cls, "read", java.nio.ByteBuffer::class.java, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val n = VoiceInject.readCalls.incrementAndGet()
                        if (n <= 3 || n % 200 == 0L) log("read(ByteBuffer,int) #$n result=${param.result}（未注入）")
                    }
                },
            )
        }.onFailure { log("hook read(ByteBuffer,int) 失败 $it") }

        runCatching {
            XposedHelpers.findAndHookMethod(
                cls, "read", java.nio.ByteBuffer::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val n = VoiceInject.readCalls.incrementAndGet()
                        if (n <= 3 || n % 200 == 0L) log("read(ByteBuffer,int,int) #$n result=${param.result}（未注入）")
                    }
                },
            )
        }.onFailure { log("hook read(ByteBuffer,int,int) 失败 $it") }

        installed.add("AudioRecord(小爱语音注入)")
        log("已挂载 AudioRecord hook 到 $XIAOI_PACKAGE")
    }

    // ------------------------------------------------------------------
    // 5. 输入层注入：改写小爱拿到的 QueryInfo
    // ------------------------------------------------------------------

    /**
     * 输入层 hook。
     *
     * 上一版只挂 QueryInfo 构造器：结果发现构造时 queryText 还是空的
     * （小爱是先建一个空 QueryInfo，随后通过 setter / notify 方法填内容），
     * 所以改为挂"文本入口方法"，在方法调用前直接改写参数。
     */
    private fun hookQueryInput(lp: XC_LoadPackage.LoadPackageParam) {
        // [类名, 方法名]，混淆名随版本变化，找不到就跳过并记日志
        val targets = listOf(
            "y00.u0" to "setQueryText",
            "b20.h" to "notifyQueryText",
            "b20.h" to "addQueryCard",
            "b20.h" to "addQueryCardFromEditBar",
            "com.xiaomi.voiceassistant.u1\$a" to "notifyQueryText",
            "sh0.n1" to "notifyQueryText",
        )
        var hooked = 0
        for ((clsName, methodName) in targets) {
            val cls = XposedHelpers.findClassIfExists(clsName, lp.classLoader) ?: continue
            runCatching {
                val set = XposedBridge.hookAllMethods(cls, methodName, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching { onQueryTextEntry(clsName, methodName, param) }
                            .onFailure { log("QueryText hook 异常 $it") }
                    }
                })
                if (!set.isNullOrEmpty()) {
                    hooked += set.size
                    log("输入层 hook: $clsName.$methodName (${set.size} 个重载)")
                }
            }.onFailure { log("hook $clsName.$methodName 失败 $it") }
        }
        // 唤起来源全串：小爱判"要不要交棒微信"看的就是它
        runCatching {
            val a6 = XposedHelpers.findClassIfExists("com.xiaomi.voiceassistant.utils.a6", lp.classLoader)
            if (a6 != null) {
                for (m in listOf("getWakeUpOrigin", "getQueryOrigin", "getLastQueryOrigin")) {
                    val set = XposedBridge.hookAllMethods(a6, m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val cur = param.result as? String
                            val ov = QueryInject.wakeOriginOverride
                            if (ov != null && cur != ov) {
                                param.result = ov
                                if (QueryInject.bumpHookCount() % 20L == 1L) log("★ 唤起来源覆盖 -> $ov")
                            }
                        }
                    })
                    if (!set.isNullOrEmpty()) log("来源读取 hook: a6.$m (${set.size})")
                }
            }
        }.onFailure { log("挂来源读取 hook 失败 $it") }

        // 查询来源（文字 / 语音）—— 小能的另一道门禁
        for ((clsName, methodName) in listOf(
            "com.xiaomi.voiceassistant.utils.a6" to "setQueryOrigin",
            "com.xiaomi.voiceassistant.utils.a6" to "setWakeUpOrigin",
            "com.xiaomi.voiceassistant.utils.a6" to "setLastQueryOrigin",
        )) {
            val cls = XposedHelpers.findClassIfExists(clsName, lp.classLoader) ?: continue
            runCatching {
                val set = XposedBridge.hookAllMethods(cls, methodName, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val v = param.args?.firstOrNull() as? String ?: return
                        log("QueryOrigin " + methodName + " = " + v)
                        val override = QueryInject.takeOriginOverride()
                        if (override != null) {
                            param.args[0] = override
                            log("★ QueryOrigin 覆盖 -> " + override)
                        }
                    }
                })
                if (!set.isNullOrEmpty()) {
                    hooked += set.size
                    log("来源 hook: $clsName.$methodName (${set.size})")
                }
            }.onFailure { log("hook $clsName.$methodName 失败 $it") }
        }

        if (hooked > 0) {
            installed.add("输入层注入($hooked 个入口)")
            log("已挂载输入层 hook：$hooked 个入口方法")
        } else {
            log("未找到任何输入层入口方法，输入层注入不可用")
        }
    }

    private fun onQueryTextEntry(clsName: String, methodName: String, param: XC_MethodHook.MethodHookParam) {
        val args = param.args ?: return
        val idx = args.indexOfFirst { it is String }
        if (idx < 0) return
        val text = args[idx] as String
        if (text.isEmpty()) return

        // 关键一步：把"来自 AI 输入框"标记清掉，让小爱以为这是语音说出来的。
        // 实测文字指令会被回绝："微信发消息…当前仅支持语音对话方式"。
        if (clsName == "y00.u0") {
            val obj = param.thisObject ?: return
            val before = runCatching { XposedHelpers.getBooleanField(obj, "d") }.getOrDefault(false)
            if (before) {
                XposedHelpers.setBooleanField(obj, "d", false)
                log("★ 来源伪造：isFromAiInputPage true -> false（" + text.take(30) + "）")
            }
        }

        val n = QueryInject.bumpHookCount()
        if (n <= 20 || n % 20 == 0L) {
            log("输入入口 #" + n + " " + clsName + "." + methodName + " args[" + idx + "]=" + text.take(60))
        }

        // 排队里有指令就替换掉，让小爱执行我们的目标而不是触发用的占位词
        val pending = QueryInject.takePending()
        if (pending != null) {
            args[idx] = pending
            QueryInject.note("已替换 $clsName.$methodName -> ${pending.take(40)}")
            log("★ 输入层注入：" + clsName + "." + methodName + " [" + text.take(30) + "] -> 「" + pending.take(60) + "」")
        }
    }

    // ------------------------------------------------------------------
    // 6. MediaProjection 授权弹窗自动确认
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
        // 显式组件：MIUI 会丢弃隐式的"非保护广播"（见 Bridge 中的说明）
        i.setClassName(HOST_PACKAGE, "com.dsh.phoneact.xposed.XposedEventReceiver")
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

    private fun log(msg: String) = Bridge.log(msg, "module")

    companion object {
        private const val HOST_PACKAGE = "com.dsh.phoneact"
        private const val XIAOI_PACKAGE = "com.miui.voiceassist"
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
        @Volatile private var traceCount = 0
    }
}
