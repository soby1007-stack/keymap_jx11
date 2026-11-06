package com.soby.jx11keymapper

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.Gravity
import android.widget.TextView

/**
 * 페어링 코드 입력 안내용 PiP(작은 떠 있는 창).
 * 설정의 '페어링 코드로 기기 페어링'을 누르는 동안 이 창은 구석에 떠 있기만 하고 설정 화면 터치를 막지 않는다.
 * 6자리 코드가 뜬 뒤 이 창을 눌러 나오는 [코드 입력] 버튼(또는 확대 버튼)을 누르면 코드 입력 창(PairInputActivity)이 열린다.
 * PiP 를 못 쓰는 기기면 바로 입력 창을 연다.
 * PiP 창 자체는 키보드 입력을 받을 수 없어서, 입력은 PairInputActivity 가 맡는다.
 */
class PairPipActivity : Activity() {

    companion object {
        private val main = Handler(Looper.getMainLooper())
        @Volatile private var current: PairPipActivity? = null

        /** 앱 화면이 앞에 있을 때 호출 (백그라운드 시작 제한) */
        fun open(ctx: Context, test: Boolean = false) {
            ctx.startActivity(
                Intent(ctx, PairPipActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(PairInputActivity.EXTRA_TEST, test)
            )
        }

        fun close() {
            main.post { current?.finish() }
        }
    }

    private var test = false
    private var entered = false
    private var wasPip = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        test = intent.getBooleanExtra(PairInputActivity.EXTRA_TEST, false)
        Diag.i("PAIRPIP", "PiP 안내 창 시작 (test=$test)")
        val d = resources.displayMetrics.density
        setContentView(TextView(this).apply {
            text = "눌러서\n코드 입력"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke((3 * d).toInt(), Color.BLACK)
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        test = intent.getBooleanExtra(PairInputActivity.EXTRA_TEST, false)
    }

    override fun onResume() {
        super.onResume()
        if (!entered) {
            entered = true
            main.post { enterPip() }
        }
    }

    private fun inputIntent() = Intent(this, PairInputActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .putExtra(PairInputActivity.EXTRA_TEST, test)

    private fun enterPip() {
        if (isFinishing) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            fallback("이 기기는 PiP 미지원")
            return
        }
        try {
            val pi = PendingIntent.getActivity(
                this, 5001, inputIntent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val action = RemoteAction(
                Icon.createWithResource(this, android.R.drawable.ic_menu_edit),
                "코드 입력", "페어링 코드 입력", pi
            )
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .setActions(listOf(action))
                .build()
            val ok = enterPictureInPictureMode(params)
            Diag.i("PAIRPIP", "PiP 진입 결과=$ok")
            if (!ok) fallback("PiP 진입 거부")
        } catch (t: Throwable) {
            Diag.e("PAIRPIP", "PiP 진입 실패 → 입력 창으로 대체", t)
            fallback("PiP 오류: ${t.message}")
        }
    }

    private fun fallback(reason: String) {
        Diag.w("PAIRPIP", "PiP 대신 입력 창을 바로 엽니다: $reason")
        try {
            startActivity(inputIntent())
        } catch (t: Throwable) {
            Diag.e("PAIRPIP", "입력 창 열기 실패", t)
        }
        finish()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        Diag.d("PAIRPIP", "PiP 모드 변경: $isInPictureInPictureMode")
        if (isInPictureInPictureMode) {
            wasPip = true
        } else if (wasPip && !isFinishing) {
            // 확대 버튼 등으로 PiP 가 풀림 → 전체 화면 대신 입력 창을 연다 (설정 화면이 멈추지 않게)
            wasPip = false
            fallback("PiP 확대")
        }
    }

    override fun onDestroy() {
        if (current === this) current = null
        Diag.i("PAIRPIP", "PiP 안내 창 닫힘")
        super.onDestroy()
    }
}
