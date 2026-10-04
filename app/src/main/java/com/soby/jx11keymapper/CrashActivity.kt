package com.soby.jx11keymapper

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** 앱이 비정상 종료되면 별도 프로세스(:crash)에서 원인 체인과 최근 로그를 보여준다. */
class CrashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = try {
            File(filesDir, "crash.txt").readText()
        } catch (e: Exception) {
            "크래시 기록을 읽지 못했습니다: ${e.message}"
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(TextView(this).apply {
            text = "⚠️ 앱이 비정상 종료되었습니다"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(32, 32, 32, 8)
        })

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(t: String, f: () -> Unit) {
            val b = Button(this)
            b.text = t
            b.setOnClickListener { f() }
            bar.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        add("복사") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("jx11-crash", report))
            Toast.makeText(this, "복사됨", Toast.LENGTH_SHORT).show()
        }
        add("공유") {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, report)
            }
            startActivity(Intent.createChooser(i, "오류 공유"))
        }
        add("닫기") { finish() }
        root.addView(bar)

        val tv = TextView(this).apply {
            text = report
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setPadding(24, 12, 24, 12)
        }
        val sv = ScrollView(this)
        sv.addView(tv)
        root.addView(sv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }
}
