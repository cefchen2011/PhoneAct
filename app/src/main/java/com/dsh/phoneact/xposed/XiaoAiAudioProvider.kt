package com.dsh.phoneact.xposed

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.dsh.phoneact.core.XiaoAiVoice

/**
 * 把合成的语音 PCM 交给被注入的小爱进程。
 *
 * 为什么用 ContentProvider：跨进程传几百 KB 的音频，
 * 广播有 Binder 1MB 限制、文件共享又受 SELinux 与应用沙箱限制，
 * 而 openFile() 走 ParcelFileDescriptor 最稳，且不受大小限制。
 *
 * 数据是"用户自己下的指令"的 TTS 音频，短时存在，不含隐私，因此不额外设权限。
 */
class XiaoAiAudioProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    /** content://<auth>/pcm?i=<轮次> —— 多轮对话每轮取一段。 */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val i = uri.getQueryParameter("i")?.toIntOrNull() ?: 0
        val f = XiaoAiVoice.clipFile(i)
        if (!f.exists() || f.length() == 0L) return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle =
        Bundle().apply {
            putString("meta", XiaoAiVoice.metaJson().toString())
            putString("query", com.dsh.phoneact.core.XiaoAi.pendingJson().toString())
        }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String = "application/octet-stream"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
