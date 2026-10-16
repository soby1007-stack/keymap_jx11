package com.soby.jx11keymapper

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager

/**
 * E-ink 잔상 제거: 전체 화면을 검정→흰색으로 잠깐 덮는다 (기기 전용 API 없이 어디서나 동작).
 * '다른 앱 위에 표시' 권한 필요 (셸 연결 시 appops 로 자동 허용).
 */
object EinkRefresh {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    const val STEP_MS = 260L

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
            var v: View? = null
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            try {
                val view = View(app).apply { setBackgroundColor(Color.BLACK) }
                v = view
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.OPAQUE
                )
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                wm.addView(view, lp)
                main.postDelayed({ view.setBackgroundColor(Color.WHITE) }, STEP_MS)
                main.postDelayed({
                    try { wm.removeView(view) } catch (t: Throwable) { Diag.w("EINK", "오버레이 제거 실패: ${t.message}") }
                    busy = false
                }, STEP_MS * 2)
                Diag.i("EINK", "화면 새로고침 (검정→흰색)")
            } catch (t: Throwable) {
                Diag.e("EINK", "화면 새로고침 실패", t)
                try { v?.let { wm.removeView(it) } } catch (e: Throwable) { /* 무시 */ }
                busy = false
            }
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
