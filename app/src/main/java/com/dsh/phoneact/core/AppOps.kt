package com.dsh.phoneact.core

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.dsh.phoneact.PhoneActApp
import org.json.JSONArray
import org.json.JSONObject

/** 应用级操作：列出、启动、停止、安装、卸载、当前前台应用。 */
object AppOps {

    data class AppInfo(
        val packageName: String,
        val label: String,
        val versionName: String,
        val system: Boolean,
        val enabled: Boolean,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("packageName", packageName)
            .put("label", label)
            .put("versionName", versionName)
            .put("system", system)
            .put("enabled", enabled)
    }

    private val pm: PackageManager get() = PhoneActApp.instance.packageManager

    fun list(filter: String? = null, includeSystem: Boolean = false, limit: Int = 300): List<AppInfo> {
        val pkgs = runCatching {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }.getOrDefault(emptyList())
        val out = ArrayList<AppInfo>(pkgs.size)
        for (p in pkgs) {
            val ai: ApplicationInfo = p.applicationInfo ?: continue
            val sys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (sys && !includeSystem) continue
            val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(p.packageName)
            if (!filter.isNullOrBlank()) {
                val f = filter.lowercase()
                if (!p.packageName.lowercase().contains(f) && !label.lowercase().contains(f)) continue
            }
            out.add(AppInfo(p.packageName, label, p.versionName ?: "", sys, ai.enabled))
            if (out.size >= limit) break
        }
        return out.sortedBy { it.label }
    }

    fun launch(pkg: String): ActionResult {
        val ctx: Context = PhoneActApp.instance
        val intent = pm.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            val ok = runCatching { ctx.startActivity(intent); true }.getOrDefault(false)
            if (ok) return ActionResult(true, "am", "已启动 $pkg")
        }
        if (!RootShell.available) RootShell.probe()
        if (RootShell.available) {
            val r = RootShell.run("monkey -p $pkg -c android.intent.category.LAUNCHER 1", 15000)
            return ActionResult(r.ok, "root", r.stdout.take(200))
        }
        return ActionResult(false, "none", "无法启动 $pkg")
    }

    fun startActivity(component: String, action: String? = null, data: String? = null): ActionResult {
        if (!RootShell.available) RootShell.probe()
        if (!RootShell.available) return ActionResult(false, "none", "root 不可用")
        val sb = StringBuilder("am start -n $component")
        if (!action.isNullOrBlank()) sb.append(" -a $action")
        if (!data.isNullOrBlank()) sb.append(" -d '$data'")
        val r = RootShell.run(sb.toString(), 20000)
        return ActionResult(r.ok, "root", r.stdout.take(300))
    }

    fun forceStop(pkg: String): ActionResult {
        if (!RootShell.available) RootShell.probe()
        if (!RootShell.available) return ActionResult(false, "none", "root 不可用")
        val r = RootShell.run("am force-stop $pkg", 12000)
        return ActionResult(r.ok, "root", r.stdout.take(200))
    }

    fun install(apkPath: String, reinstall: Boolean = true): ActionResult {
        if (!RootShell.available) RootShell.probe()
        if (!RootShell.available) return ActionResult(false, "none", "root 不可用")
        val flag = if (reinstall) "-r " else ""
        val r = RootShell.run("pm install " + flag + "'" + apkPath + "'", 120000)
        return ActionResult(r.ok && r.stdout.contains("Success"), "root", r.stdout.take(300))
    }

    fun uninstall(pkg: String, keepData: Boolean = false): ActionResult {
        if (!RootShell.available) RootShell.probe()
        if (!RootShell.available) return ActionResult(false, "none", "root 不可用")
        val r = RootShell.run("pm uninstall ${if (keepData) "-k " else ""}$pkg", 60000)
        return ActionResult(r.ok && r.stdout.contains("Success"), "root", r.stdout.take(300))
    }

    fun currentForeground(): Pair<String, String> {
        // 优先用无障碍事件推断（零成本），再回退 dumpsys
        val tracked = FrameHub.foregroundPackage
        if (tracked.isNotBlank()) return tracked to FrameHub.foregroundActivity
        if (!RootShell.available) return "" to ""
        val r = RootShell.run("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -n 2", 12000)
        val line = r.stdout.lineSequence().firstOrNull { it.contains("/") } ?: return "" to ""
        val seg = line.substringAfter("u0 ").substringBefore(" ").trim()
        val pkg = seg.substringBefore("/")
        val act = seg.substringAfter("/", "")
        return pkg to act
    }

    fun screenState(): JSONObject {
        if (!RootShell.available) RootShell.probe()
        val o = JSONObject()
        val d = if (RootShell.available) RootShell.run("dumpsys deviceidle | head -n 2; dumpsys power | grep -E 'mWakefulness=|mScreenOn' | head -n 3", 15000).stdout else ""
        o.put("wakefulness", d.lineSequence().firstOrNull { it.contains("mWakefulness=") }?.substringAfter("mWakefulness=")?.trim() ?: "unknown")
        val wm = if (RootShell.available) RootShell.run("wm size; wm density", 12000).stdout else ""
        o.put("wm", wm.trim())
        o.put("rotation", if (RootShell.available) RootShell.run("dumpsys window | grep -E 'mRotation|mCurrentRotation' | head -n 2", 15000).stdout.trim() else "")
        return o
    }

    fun packagesJson(list: List<AppInfo>): JSONArray = JSONArray().apply { list.forEach { put(it.toJson()) } }
}
