package com.soby.jx11keymapper

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 전방위 진단 로그.
 * 레벨: V(메모리에만, 이벤트 단위) / D / I / W / E(파일+메모리)
 * 단계 태그: APP, SHIZUKU, BIND, SVC, DEVICE, READ, EVENT, MAP, FIRE, ACTION, LEARN, UI, CRASH
 */
object Diag {
    private const val TAG = "JX11"
    private const val MAX_MEM = 2000
    private const val MAX_FILE = 1_000_000L

    private val mem = ArrayDeque<String>()
    private var file: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(ctx: Context) {
        synchronized(this) {
            val f = File(ctx.filesDir, "jx11.log")
            try {
                if (f.exists() && f.length() > MAX_FILE) {
                    f.renameTo(File(ctx.filesDir, "jx11.log.old"))
                }
            } catch (e: Exception) {
                Log.w(TAG, "로그 회전 실패: $e")
            }
            file = f
        }
        i("APP", "시작 v${BuildConfig.VERSION_NAME} / ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
    }

    fun log(level: Char, stage: String, msg: String) {
        synchronized(this) {
            val line = "${fmt.format(Date())} $level/$stage $msg"
            if (mem.size >= MAX_MEM) mem.removeFirst()
            mem.addLast(line)
            if (level != 'V') {
                try {
                    file?.appendText(line + "\n")
                } catch (e: Exception) {
                    // 파일 기록 실패는 무시 (메모리 로그는 유지)
                }
            }
        }
        when (level) {
            'E' -> Log.e(TAG, "$stage $msg")
            'W' -> Log.w(TAG, "$stage $msg")
            else -> Log.d(TAG, "$stage $msg")
        }
    }

    fun v(stage: String, msg: String) = log('V', stage, msg)
    fun d(stage: String, msg: String) = log('D', stage, msg)
    fun i(stage: String, msg: String) = log('I', stage, msg)
    fun w(stage: String, msg: String) = log('W', stage, msg)

    fun e(stage: String, msg: String, t: Throwable? = null) {
        if (t == null) {
            log('E', stage, msg)
        } else {
            log('E', stage, "$msg | ${chain(t)}")
            val frames = Log.getStackTraceString(t).lines().take(14).joinToString(" // ")
            log('D', stage, "스택: $frames")
        }
    }

    /** 원인 체인: A ⇐ caused by B ⇐ caused by C */
    fun chain(t: Throwable?): String {
        val sb = StringBuilder()
        val seen = HashSet<Throwable>()
        var cur = t
        var depth = 0
        while (cur != null && seen.add(cur) && depth < 10) {
            if (depth > 0) sb.append("  ⇐ caused by ")
            sb.append(cur.javaClass.name).append(": ").append(cur.message)
            cur = cur.cause
            depth++
        }
        return sb.toString()
    }

    fun dump(maxLines: Int = 600): String = synchronized(this) {
        mem.toList().takeLast(maxLines).joinToString("\n")
    }

    fun clear() {
        synchronized(this) {
            mem.clear()
            try {
                file?.writeText("")
            } catch (e: Exception) {
                Log.w(TAG, "로그 파일 비우기 실패: $e")
            }
        }
    }

    fun buildCrashReport(t: Thread, e: Throwable): String {
        val sb = StringBuilder()
        sb.append("=== JX-11 키맵퍼 크래시 ===\n")
        sb.append("시각: ").append(Date()).append('\n')
        sb.append("스레드: ").append(t.name).append('\n')
        sb.append("앱 v${BuildConfig.VERSION_NAME} / ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
        sb.append("원인 체인: ").append(chain(e)).append("\n\n")
        sb.append(Log.getStackTraceString(e)).append('\n')
        sb.append("--- 설정 ---\n").append(Cfg.describe()).append('\n')
        sb.append("--- 최근 로그 ---\n").append(dump(120))
        return sb.toString()
    }
}
