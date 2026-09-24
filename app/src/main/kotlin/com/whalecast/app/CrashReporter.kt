package com.whalecast.app

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把未捕获异常记录下来，并在下次启动时展示在首页。
 *
 * **为什么需要它**：主人的设备环境里没有 adb，闪退时看不到任何 logcat。
 * 有了它，崩溃堆栈会直接出现在 App 首页，一眼就能定位问题，
 * 而不是"投屏闪退 → 猜原因 → 改 → 再崩"这种盲人摸象。
 */
object CrashReporter {

    private const val FILE_NAME = "last-crash.txt"

    /** 在 Application.onCreate 里调用一次。 */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(appContext, thread, throwable) }
            // 仍交给默认处理器：该崩还是要崩，只是这次留下了证据
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val stack = StringWriter().also { buffer ->
            PrintWriter(buffer).use { throwable.printStackTrace(it) }
        }.toString()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        runCatching {
            File(context.filesDir, FILE_NAME).writeText("$timestamp on thread ${thread.name}\n$stack")
        }
    }

    fun read(context: Context): String? = runCatching {
        File(context.filesDir, FILE_NAME).takeIf { it.exists() }?.readText()
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}
