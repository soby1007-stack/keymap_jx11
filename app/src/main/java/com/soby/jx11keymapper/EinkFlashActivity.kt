package com.soby.jx11keymapper

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager

/**
 * E-ink 새로고침용 전체 화면: 완전히 불투명한 검정↔흰색을 설정한 시간/횟수만큼 보여준 뒤 닫힌다.
 * (오버레이 창은 시스템이 불투명도를 제한해 회색으로 보여서, 일반 액티비티로 덮는다)
 */
class EinkFlashActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var v: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            v = View(this).apply { setBackgroundColor(Color.BLACK) }
            setContentView(v)
            @Suppress("DEPRECATION")
            v.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            val black = Cfg.einkBlackMs.toLong().coerceIn(50, 3000)
            val white = Cfg.einkWhiteMs.toLong().coerceIn(50, 3000)
            val n = Cfg.einkRepeat.coerceIn(1, 5)
            var t = 0L
            repeat(n) {
                h.postDelayed({ v.setBackgroundColor(Color.BLACK) }, t)
                t += black
                h.postDelayed({ v.setBackgroundColor(Color.WHITE) }, t)
                t += white
            }
            h.postDelayed({ finish() }, t)
            Diag.i("EINK", "전체 화면 새로고침 시작: 검정 ${black}ms / 흰색 ${white}ms x$n")
        } catch (t: Throwable) {
            Diag.e("EINK", "새로고침 화면 실패", t)
            finish()
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        EinkRefresh.busy = false
        super.onDestroy()
    }
}
