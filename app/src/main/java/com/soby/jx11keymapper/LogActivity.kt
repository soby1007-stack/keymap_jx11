package com.soby.jx11keymapper

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 진단 로그 보기: 복사 / 공유 / 비우기 / 일시정지 */
class LogActivity : Activity() {

    private val h = Handler(Looper.getMainLooper())
    private lateinit var tv: TextView
    private lateinit var sv: ScrollView
    private var paused = false

    private val tick = object : Runnable {
        override fun run() {
            if (!paused) refresh()
            h.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(t: String, f: (Button) -> Unit) {
            val b = Button(this)
            b.text = t
            b.setOnClickListener { f(b) }
            bar.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        add("복사") { copy() }
        add("공유") { share() }
        add("비우기") {
            Diag.clear()
            refresh()
        }
        add("멈춤") { b ->
            paused = !paused
            b.text = if (paused) "재개" else "멈춤"
        }
        root.addView(bar)

        tv = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setPadding(24, 12, 24, 12)
        }
        sv = ScrollView(this)
        sv.addView(tv)
        root.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        h.post(tick)
    }

    override fun onPause() {
        h.removeCallbacks(tick)
        super.onPause()
    }

    private fun refresh() {
        val child: View? = sv.getChildAt(0)
        val atBottom = child == null || (child.bottom - (sv.height + sv.scrollY)) < 120
        tv.text = Diag.dump(600)
        if (atBottom) sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }

    private fun copy() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("jx11-log", Diag.dump(2000)))
        Toast.makeText(this, "로그 복사됨", Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, Diag.dump(2000))
        }
        startActivity(Intent.createChooser(i, "로그 공유"))
    }
}
