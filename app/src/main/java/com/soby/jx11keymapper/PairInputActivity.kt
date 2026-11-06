package com.soby.jx11keymapper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 페어링 코드 입력 창 — '떠 있는 창' 모양의 일반 액티비티 (오버레이 아님).
 *
 * 왜 오버레이가 아닌가: 설정의 페어링 화면은 보안상 다른 앱의 오버레이 창을 숨긴다(상단바를 내려야만 보였던 이유).
 * 일반 액티비티 창은 숨겨지지 않는다. 또 투명 배경의 작은 창이라 아래의 설정 앱은 '정지'가 아니라 '일시정지' 상태로
 * 남아서 페어링 코드 창이 닫히지 않고, 창 바깥을 누르면 아래 설정 화면으로 전달된다.
 * 상단바 알림이 아예 없는 기기(전자책 리더 등)에서도 쓸 수 있다.
 */
class PairInputActivity : Activity() {

    companion object {
        private val main = Handler(Looper.getMainLooper())
        @Volatile private var current: PairInputActivity? = null
        private const val EXTRA_TEST = "test"

        /** 앱 화면이 앞에 있을 때(버튼 누른 직후) 호출해야 시작이 허용된다 */
        fun open(ctx: Context, test: Boolean = false) {
            ctx.startActivity(
                Intent(ctx, PairInputActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(EXTRA_TEST, test)
            )
        }

        /** 열려 있을 때만 문구를 바꾸고 입력을 다시 받는다 (실패 후 재입력, 포트 입력 단계 등). 아니면 아무것도 안 한다 */
        fun update(title: String, hint: String, portOnly: Boolean) {
            main.post { current?.render(title, hint, portOnly) }
        }

        fun close() {
            main.post { current?.finish() }
        }
    }

    private var test = false
    private var title = "페어링 코드 입력"
    private var hint = "설정의 '페어링 코드로 기기 페어링'을 눌러 6자리 코드를 띄운 뒤, 그 코드를 여기에 입력하세요"
    private var portOnly = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        test = intent.getBooleanExtra(EXTRA_TEST, false)
        if (test) {
            title = "입력창 테스트"
            hint = "이 창이 설정 화면 위에 보이고 키보드로 숫자가 입력되면 정상이에요. 6자리를 넣고 [확인]을 눌러 보세요"
        }
        window.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)   // 창 바깥 터치는 아래 설정 화면으로
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            setLayout(dp(340), WindowManager.LayoutParams.WRAP_CONTENT)
            attributes = attributes.apply { y = dp(40) }
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        setFinishOnTouchOutside(false)
        Diag.i("PAIRUI", "입력 창 열림 (test=$test)")
        render(title, hint, portOnly)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        test = intent.getBooleanExtra(EXTRA_TEST, false)
        Diag.i("PAIRUI", "입력 창 다시 열림 (test=$test)")
        render(title, hint, portOnly)
    }

    override fun onDestroy() {
        if (current === this) current = null
        Diag.i("PAIRUI", "입력 창 닫힘")
        super.onDestroy()
    }

    fun render(t: String, h: String, portOnlyMode: Boolean) {
        title = t
        hint = h
        portOnly = portOnlyMode
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(2), Color.BLACK)
                cornerRadius = dp(12).toFloat()
            }
        }
        box.addView(TextView(this).apply {
            text = t
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.BLACK)
            setPadding(0, dp(4), 0, dp(6))
        })
        box.addView(TextView(this).apply {
            text = h
            textSize = 13f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, dp(8))
        })
        val code = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(if (portOnlyMode) 5 else 6))
            this.hint = if (portOnlyMode) "포트 5자리" else "6자리 코드"
            textSize = 26f
            gravity = Gravity.CENTER
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
        }
        box.addView(code, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        var portEt: EditText? = null
        if (!portOnlyMode) {
            portEt = EditText(this).apply {
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
        val status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        box.addView(status)

        val ok = Button(this).apply { text = "확인" }
        val submit: () -> Unit = {
            val c = code.text.toString().trim()
            val p = portEt?.text?.toString()?.trim().orEmpty()
            val need = if (portOnlyMode) 4 else 6
            if (c.length < need) {
                code.error = if (portOnlyMode) "포트(4~5자리)를 입력하세요" else "6자리를 입력하세요"
            } else {
                val raw = if (p.length >= 4) "$p $c" else c
                Diag.i("PAIRUI", "입력 제출 · 길이=${c.length}${if (p.isNotEmpty()) " 포트 있음" else ""} test=$test")
                if (test) {
                    Toast.makeText(this, "입력 수신 ✓ (${raw.length}자)", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    try {
                        startService(
                            Intent(this, AdbPairingService::class.java)
                                .setAction(AdbPairingService.ACTION_CODE)
                                .putExtra(AdbPairingService.EXTRA_RAW, raw)
                        )
                        // 결과(성공/실패)는 서비스가 알려 준다: 실패하면 update() 로 다시 입력 상태가 되고, 성공하면 close()
                        code.setText("")
                        ok.isEnabled = false
                        status.text = "확인 중…"
                    } catch (t: Throwable) {
                        Diag.e("PAIRUI", "서비스로 입력 전달 실패", t)
                        status.text = "전달 실패: ${t.message}"
                    }
                }
            }
        }
        ok.setOnClickListener { submit() }
        code.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else false
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        buttons.addView(ok, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(Button(this).apply {
            text = "닫기"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(buttons)
        setContentView(box)
        code.requestFocus()
    }
}
