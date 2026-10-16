package com.soby.jx11keymapper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * E-ink 잔상 제거: 전체 화면을 완전한 검정→흰색으로 덮는다 (기기 전용 API 없이 어디서나 동작).
 * 덮는 화면은 EinkFlashActivity. '다른 앱 위에 표시' 권한이 있으면 백그라운드에서도 띄울 수 있다
 * (셸 연결 시 appops 로 자동 허용).
 */
object EinkRefresh {
    private val main = Handler(Looper.getMainLooper())
    @Volatile var busy = false

    fun hasPermission(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun flash(ctx: Context) {
        val app = ctx.applicationContext
        if (!hasPermission(app)) {
            Diag.w("EINK", "'다른 앱 위에 표시' 권한 없음 → 셸 연결 후 자동 허용되거나, 설정에서 직접 허용하세요")
            return
        }
        if (busy) return
        busy = true
        main.post {
            try {
                app.startActivity(
                    Intent(app, EinkFlashActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                )
            } catch (t: Throwable) {
                Diag.e("EINK", "새로고침 화면 실행 실패", t)
                busy = false
            }
            // 안전장치: 혹시 화면이 안 뜨면 잠금이 영원히 남지 않게
            main.postDelayed({ busy = false }, 15_000)
        }
    }

    /** 설정 화면의 테스트 버튼: 권한 없으면 권한 화면으로 */
    fun test(ctx: Context) {
        if (hasPermission(ctx)) {
            flash(ctx)
        } else {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (t: Throwable) {
                Diag.e("EINK", "권한 화면 열기 실패", t)
            }
        }
    }
}
