package com.soby.jx11keymapper

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import java.io.File
import kotlin.system.exitProcess

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        Diag.init(this)
        Cfg.init(this)

        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                Diag.log('E', "CRASH", "[${t.name}] ${Diag.chain(e)}")
                File(filesDir, "crash.txt").writeText(Diag.buildCrashReport(t, e))
                // 앱이 화면에 떠 있으면 바로 크래시 화면, 백그라운드면 알림으로 안내
                try {
                    startActivity(
                        Intent(this, CrashActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                } catch (x: Throwable) {
                    Diag.w("CRASH", "크래시 화면 직접 실행 실패: ${x.message}")
                }
                notifyCrash()
            } catch (x: Throwable) {
                // 크래시 처리 중 오류는 무시하고 아래에서 종료
            }
            if (prev != null) {
                prev.uncaughtException(t, e)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    private fun notifyCrash() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("crash", "오류 알림", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(
            this, 2,
            Intent(this, CrashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        nm.notify(
            99,
            Notification.Builder(this, "crash")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("JX-11 키맵퍼 오류 발생")
                .setContentText("탭하여 오류 내용 보기")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        )
    }
}
