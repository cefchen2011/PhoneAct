package com.dsh.phoneact.xposed

import android.app.Application
import de.robv.android.xposed.XposedHelpers

/** 在被注入的任意进程里拿到 Application（用于 ContentResolver / 注册广播等）。 */
object HookContext {
    @Volatile private var cached: Application? = null

    fun app(): Application? {
        cached?.let { return it }
        val a = runCatching {
            XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null),
                "currentApplication",
            ) as? Application
        }.getOrNull()
        cached = a
        return a
    }
}
