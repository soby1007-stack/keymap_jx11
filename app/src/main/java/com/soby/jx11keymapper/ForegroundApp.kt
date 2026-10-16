package com.soby.jx11keymapper

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * 지금 화면 맨 앞에 있는 앱 알아내기 (사용 기록 접근 권한 필요).
 * 권한은 앱 화면에서 따로 켤 필요 없이, 셸 연결 후 `appops set` 으로 자동 허용한다.
 */
object ForegroundApp {
    private var lastPkg: String? = null
    private var lastQuery = 0L

    fun hasAccess(ctx: Context): Boolean = try {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    } catch (t: Throwable) {
        Diag.w("FG", "사용 기록 권한 확인 실패: ${t.message}")
        false
    }

    /** 가장 최근에 앞으로 나온 앱 패키지. 새 이벤트가 없으면 이전 값 유지. 권한 없으면 null */
    @Synchronized
    fun current(ctx: Context): String? {
        try {
            val um = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val from = if (lastQuery == 0L) now - 3_600_000L else lastQuery - 2_000L
            val ev = um.queryEvents(from, now)
            val e = UsageEvents.Event()
            var found: String? = null
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED && e.packageName != ctx.packageName) {
                    found = e.packageName
                }
            }
            lastQuery = now
            if (found != null && found != lastPkg) {
                Diag.d("FG", "앞 앱: $found")
                lastPkg = found
            }
        } catch (t: Throwable) {
            Diag.w("FG", "앞 앱 확인 실패: ${t.message}")
        }
        return lastPkg
    }
}
