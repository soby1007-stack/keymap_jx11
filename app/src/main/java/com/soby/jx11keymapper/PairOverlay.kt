package com.soby.jx11keymapper

import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 페어링 코드 입력용 플로팅 창 ('다른 앱 위에 표시' 권한).
 * 상단 알림바/알림 입력창을 쓸 수 없는 기기(전자책 리더 커스텀 롬 등)에서도, 설정의 '페어링 코드' 창을 그대로 둔 채
 * 화면 위에 떠 있는 입력창에 코드를 칠 수 있다. 제목 줄을 끌어 위치를 옮길 수 있다.
 * (PiP 창은 키보드 입력을 받을 수 없어서 오버레이 창으로 만든다)
 * 흑백 + 굵은 테두리라 E-ink 에서도 잘 보인다.
 */
object PairOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null

    private fun dp(v: Int) = (v * Resources.getSystem().displayMetrics.density).toInt()

    fun canShow(ctx: Context): Boolean = try {
        Settings.canDrawOverlays(ctx)
    } catch (t: Throwable) {
        false
    }

    /**
     * 입력창을 띄운다 (이미 떠 있으면 문구만 갱신). 어느 스레드에서든 호출 가능.
     * @param portOnly true 면 '연결 포트(5자리)' 입력 모드 (코드 6자리 대신)
     * @param onSubmit 입력 문자열 — 포트를 같이 적었으면 "포트 코드" 형식
     */
    fun show(ctx: Context, title: String, hint: String, portOnly: Boolean, onSubmit: (String) -> Unit) {
        val app = ctx.applicationContext
        Diag.i("OVERLAY", "표시 요청: $title · 권한=${canShow(app)}")
        main.post {
            try {
                showOnMain(app, title, hint, portOnly, onSubmit)
            } catch (t: Throwable) {
                Diag.e("OVERLAY", "플로팅 입력창 표시 실패 (알림으로만 입력 가능)", t)
                root = null
            }
        }
    }

    fun hide() {
        main.post { hideOnMain() }
    }

    private fun hideOnMain() {
        val r = root ?: return
        try {
            val imm = r.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(r.windowToken, 0)
        } catch (t: Throwable) {
            // 무시
        }
        try {
            wm?.removeView(r)
        } catch (t: Throwable) {
            Diag.w("OVERLAY", "창 제거 실패: ${t.message}")
        }
        root = null
        Diag.i("OVERLAY", "플로팅 입력창 닫힘")
    }

    private fun showOnMain(app: Context, title: String, hint: String, portOnly: Boolean, onSubmit: (String) -> Unit) {
        hideOnMain()   // 모드가 바뀌면 새로 만든다
        val ctx = ContextThemeWrapper(app, android.R.style.Theme_DeviceDefault_Light)
        val manager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = manager

        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(2), Color.BLACK)
                cornerRadius = dp(12).toFloat()
            }
        }
        val lp = WindowManager.LayoutParams(
            dp(320),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,   // 포커스는 받되(키보드 입력), 바깥 터치는 아래 화면으로 전달
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(72)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        params = lp

        val titleTv = TextView(ctx).apply {
            text = "☰  $title"
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
            setPadding(0, dp(4), 0, dp(6))
        }
        // 제목 줄을 끌어서 이동
        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        titleTv.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = ev.rawX
                    sy = ev.rawY
                    ox = lp.x
                    oy = lp.y
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = ox + (ev.rawX - sx).toInt()
                    lp.y = oy + (ev.rawY - sy).toInt()
                    try {
                        manager.updateViewLayout(box, lp)
                    } catch (t: Throwable) {
                        // 무시
                    }
                }
            }
            true
        }
        box.addView(titleTv)
        box.addView(TextView(ctx).apply {
            text = hint
            textSize = 13f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, dp(8))
        })

        val code = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(if (portOnly) 5 else 6))
            this.hint = if (portOnly) "포트 5자리" else "6자리 코드"
            textSize = 26f
            gravity = Gravity.CENTER
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
        }
        box.addView(code, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        var portEt: EditText? = null
        if (!portOnly) {
            portEt = EditText(ctx).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(InputFilter.LengthFilter(5))
                this.hint = "포트 (코드 창에 'IP 주소 및 포트'가 보일 때만)"
                textSize = 13f
                gravity = Gravity.CENTER
                setSingleLine(true)
                setTextColor(Color.BLACK)
                setHintTextColor(Color.GRAY)
            }
            box.addView(portEt, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val submit: () -> Unit = {
            val c = code.text.toString().trim()
            val p = portEt?.text?.toString()?.trim().orEmpty()
            val need = if (portOnly) 4 else 6
            if (c.length < need) {
                code.error = if (portOnly) "포트(4~5자리)를 입력하세요" else "6자리를 입력하세요"
            } else {
                val raw = if (p.length >= 4) "$p $c" else c
                Diag.i("OVERLAY", "플로팅 입력 제출 · 길이=${c.length}${if (p.isNotEmpty()) " 포트 있음" else ""}")
                hideOnMain()
                onSubmit(raw)
            }
        }
        code.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else false
        }

        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        buttons.addView(Button(ctx).apply {
            text = "확인"
            setOnClickListener { submit() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(Button(ctx).apply {
            text = "닫기"
            setOnClickListener { hideOnMain() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(buttons)

        manager.addView(box, lp)
        root = box
        Diag.i("OVERLAY", "플로팅 입력창 표시: $title (portOnly=$portOnly)")

        code.postDelayed({
            try {
                code.requestFocus()
                val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(code, InputMethodManager.SHOW_IMPLICIT)
            } catch (t: Throwable) {
                Diag.w("OVERLAY", "키보드 표시 실패: ${t.message}")
            }
        }, 250)
    }
}
