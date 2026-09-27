package com.dsh.phoneact.xposed

import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

/**
 * Xposed 模块运行状态。
 *
 * 注意：LSPosed 用独立的 ClassLoader 加载模块代码，模块里的静态字段与宿主 App 并不共享，
 * 因此这里的状态全部由 [XposedEventReceiver] 从模块的广播上报中聚合而来。
 */
object XposedStatus {

    @Volatile var frameworkDetected: Boolean = false
        private set

    @Volatile var bridgeVersion: Int = 0
        private set

    /** 宿主 App 自身进程是否被注入（说明作用域包含本应用）。 */
    @Volatile var appProcessActive: Boolean = false
        private set

    @Volatile var lastReportAt: Long = 0
        private set

    @Volatile var lastProcess: String = ""
        private set

    @Volatile var lastHookError: String = ""

    private val processes: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet<String>())
    private val hooks: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet<String>())

    val active: Boolean get() = frameworkDetected

    fun markAlive(pkg: String, process: String, hookCsv: String, bridge: Int) {
        frameworkDetected = true
        if (bridge > 0) bridgeVersion = bridge
        lastReportAt = System.currentTimeMillis()
        lastProcess = process
        if (pkg == "com.dsh.phoneact" || process == "com.dsh.phoneact") appProcessActive = true
        if (process.isNotBlank()) processes.add(process)
        if (hookCsv.isNotBlank()) {
            hookCsv.split(',').forEach { if (it.isNotBlank()) hooks.add(it.trim()) }
        }
        com.dsh.phoneact.core.Lg.i("Xposed 上报: pkg=$pkg process=$process bridge=$bridge hooks=${hooks.size}")
    }

    fun processList(): List<String> = processes.toList()
    fun hookList(): List<String> = hooks.toList()

    fun toJson(): JSONObject = JSONObject().apply {
        put("active", active)
        put("frameworkDetected", frameworkDetected)
        put("appProcessActive", appProcessActive)
        put("bridgeVersion", bridgeVersion)
        put("lastReportAt", lastReportAt)
        put("lastProcess", lastProcess)
        put("processCount", processes.size)
        put("processes", JSONArray(processList()))
        put("hookCount", hooks.size)
        put("hooks", JSONArray(hookList()))
        if (lastHookError.isNotEmpty()) put("lastHookError", lastHookError)
    }
}
