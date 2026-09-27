package com.dsh.phoneact.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.dsh.phoneact.PhoneActApp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 剪贴板读写。
 *
 * 用途：`root input text` 无法输入非 ASCII 字符，而部分应用（如微信）还会屏蔽无障碍节点树，
 * 导致 ACTION_SET_TEXT 不可用。此时「写剪贴板 + KEYCODE_PASTE(279)」是可靠的通用方案。
 *
 * 注意：Android 10+ 对剪贴板读取有焦点限制，但写入不受限；前台应用读取本应用写入的内容是允许的。
 * 主线程要求：ClipboardManager 在部分 ROM 上要求主线程调用。
 */
object Clipboard {

    private val main = Handler(Looper.getMainLooper())

    fun setText(text: String): Boolean = onMain {
        val cm = PhoneActApp.instance.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("phoneact", text))
        true
    } ?: false

    fun getText(): String? = onMain {
        val cm = PhoneActApp.instance.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (!cm.hasPrimaryClip()) null
        else cm.primaryClip?.getItemAt(0)?.coerceToText(PhoneActApp.instance)?.toString()
    }

    private fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return runCatching(block).getOrNull()
        var result: T? = null
        val latch = CountDownLatch(1)
        main.post {
            result = runCatching(block).getOrNull()
            latch.countDown()
        }
        latch.await(3000, TimeUnit.MILLISECONDS)
        return result
    }
}
