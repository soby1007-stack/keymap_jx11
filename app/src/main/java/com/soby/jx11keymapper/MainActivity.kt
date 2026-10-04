package com.soby.jx11keymapper

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private val h = Handler(Looper.getMainLooper())
    private lateinit var tvShizuku: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvLive: TextView
    private lateinit var tvDevices: TextView
    private lateinit var tvLearn: TextView
    private lateinit var tableBox: LinearLayout
    private lateinit var swRun: Switch
    private lateinit var etFilter: EditText
    private var updating = false
    private var renderedVersion = -1

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        runOnUiThread {
            val ok = result == PackageManager.PERMISSION_GRANTED
            Diag.i("SHIZUKU", "권한 요청 결과: ${if (ok) "허용" else "거부"}")
            toast(if (ok) "Shizuku 권한 허용됨" else "Shizuku 권한이 거부됨")
            KeymapService.instance?.onPermissionChanged()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            h.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            Shizuku.addRequestPermissionResultListener(permListener)
        } catch (t: Throwable) {
            Diag.e("UI", "권한 리스너 등록 실패", t)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(28))
        }

        // ── 상태 ──
        root.addView(title("상태"))
        tvShizuku = label("")
        tvStatus = label("")
        tvLive = label("")
        tvDevices = label("")
        root.addView(tvShizuku)
        root.addView(tvStatus)
        root.addView(tvLive)
        root.addView(tvDevices)
        root.addView(button("Shizuku 권한 요청") { requestShizuku() })

        swRun = Switch(this).apply {
            text = "키맵퍼 켜기"
            textSize = 16f
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, checked -> if (!updating) onRunToggle(checked) }
        }
        root.addView(swRun)

        // ── 키 매핑 표 (기능 | 키 매핑 | ＋ | －) ──
        root.addView(title("키 매핑 (JX-11)"))
        tvLearn = label("").apply {
            setTextColor(0xFF5D4037.toInt())
            setBackgroundColor(0xFFFFF3C4.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(10))
            visibility = View.GONE
            setOnClickListener { Learner.cancel() }
        }
        root.addView(tvLearn)
        tableBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(tableBox)
        root.addView(label("＋ 를 누르고 JX-11 입력 → 등록 (한 기능에 여러 개 가능) · － 는 삭제\n" +
            "⚡ 는 Shizuku 연결이 필요한 동작 · 같은 입력은 한 기능에만 지정돼요").apply { textSize = 12f })
        root.addView(button("표 기본값으로") {
            Cfg.resetRules()
            toast("기본값으로 복원")
        })

        // ── 민감도 ──
        root.addView(title("민감도"))
        root.addView(slider("스크롤 몇 칸에 1회 동작", 39,
            { ((Cfg.threshold / 0.25f).roundToInt() - 1).coerceIn(0, 39) },
            { Cfg.threshold = (it + 1) * 0.25f },
            { "%.2f칸 (작을수록 민감)".format((it + 1) * 0.25f) }))
        root.addView(slider("연속 동작 간격", 50,
            { (Cfg.cooldownMs / 10).coerceIn(0, 50) },
            { Cfg.cooldownMs = it * 10 },
            { "${it * 10}ms (작을수록 빠름)" }))
        root.addView(slider("볼륨·방향키 한 번에 반복", 4,
            { (Cfg.stepsPerFire - 1).coerceIn(0, 4) },
            { Cfg.stepsPerFire = it + 1 },
            { "${it + 1}번" }))

        // ── 볼륨 동작 ──
        root.addView(title("볼륨 동작"))
        root.addView(sw("미디어 볼륨 고정 (끄면 실제 볼륨키와 동일)", { Cfg.mediaFixed }, { Cfg.mediaFixed = it }))
        root.addView(sw("볼륨 UI 표시", { Cfg.showUi }, { Cfg.showUi = it }))
        root.addView(sw("화면 꺼져도 확실히 동작 (배터리 더 사용)", { Cfg.wakeLock }, {
            Cfg.wakeLock = it
            KeymapService.instance?.applyWake()
        }))

        // ── 입력 장치 ──
        root.addView(title("입력 장치"))
        root.addView(label("장치 이름에 이 글자가 들어 있으면 읽습니다 (비우면 전체)"))
        etFilter = EditText(this).apply {
            setSingleLine()
            setText(Cfg.deviceFilter)
            hint = "예: JX"
        }
        root.addView(etFilter)
        root.addView(row(
            button("적용") { applyFilter() },
            button("장치 목록") { showDeviceList() }
        ))
        root.addView(row(
            button("볼륨 테스트 ↑↓") {
                VolumeAction.apply(this, true, 1)
                h.postDelayed({ VolumeAction.apply(this, false, 1) }, 600)
            },
            button("로그 보기") { startActivity(Intent(this, LogActivity::class.java)) }
        ))

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        if (Cfg.enabled && KeymapService.instance == null) {
            Diag.i("UI", "이전에 켜 둔 상태 → 서비스 자동 시작")
            try {
                KeymapService.start(this)
            } catch (t: Throwable) {
                Diag.e("UI", "서비스 자동 시작 실패", t)
            }
        }
        h.post(tick)
    }

    override fun onPause() {
        h.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(permListener)
        } catch (t: Throwable) {
            Diag.w("UI", "권한 리스너 해제 실패: ${t.message}")
        }
        super.onDestroy()
    }

    // ───────── 표 ─────────

    private fun renderTable() {
        tableBox.removeAllViews()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFFE3ECF7.toInt())
        }
        head.addView(cell("기능", 2.3f, 13f, true))
        head.addView(cell("키 매핑", 3.3f, 13f, true))
        head.addView(cell("추가", 0.9f, 13f, true, Gravity.CENTER))
        head.addView(cell("삭제", 0.9f, 13f, true, Gravity.CENTER))
        tableBox.addView(head)

        for (act in Act.values()) {
            val rules = Cfg.rulesOf(act)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(cell(act.label + (if (act.needsShell) " ⚡" else ""), 2.3f, 13f, true))
            row.addView(cell(
                if (rules.isEmpty()) "-" else rules.joinToString(", ") { it.label() },
                3.3f, 12f, false, Gravity.CENTER_VERTICAL, if (rules.isEmpty()) 0xFF999999.toInt() else 0xFF1A237E.toInt()
            ))
            row.addView(smallButton("＋", true) { startLearn(act, false) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f))
            row.addView(smallButton("－", rules.isNotEmpty()) { onMinus(act, rules) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f))
            tableBox.addView(row)
            tableBox.addView(View(this).apply {
                setBackgroundColor(0xFFDDDDDD.toInt())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })
        }
    }

    private fun cell(
        t: String, w: Float, size: Float, bold: Boolean,
        grav: Int = Gravity.CENTER_VERTICAL, color: Int = 0xFF222222.toInt()
    ): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = size
        tv.setTextColor(color)
        if (bold) tv.setTypeface(tv.typeface, Typeface.BOLD)
        tv.gravity = grav
        tv.setPadding(dp(6), dp(10), dp(6), dp(10))
        tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w)
        return tv
    }

    private fun smallButton(t: String, enabled: Boolean, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.textSize = 16f
        b.minWidth = 0
        b.minimumWidth = 0
        b.minHeight = 0
        b.minimumHeight = dp(40)
        b.setPadding(0, 0, 0, 0)
        b.isEnabled = enabled
        b.setOnClickListener { onClick() }
        return b
    }

    private fun onMinus(act: Act, rules: List<Rule>) {
        if (rules.size == 1) {
            confirmDelete(act, rules[0])
        } else {
            startLearn(act, true)
        }
    }

    private fun confirmDelete(act: Act, rule: Rule) {
        AlertDialog.Builder(this)
            .setTitle("삭제 확인")
            .setMessage("'${act.label}'에서 '${rule.label()}' 지정을 삭제할까요?")
            .setPositiveButton("삭제") { _, _ ->
                Cfg.removeRule(act, rule)
                Diag.i("UI", "삭제: ${act.label} ← ${rule.label()}")
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun startLearn(act: Act, delete: Boolean) {
        if (KeymapService.instance == null) {
            toast("먼저 키맵퍼를 켜세요")
            return
        }
        Learner.begin(act, delete) { outcome, rule ->
            runOnUiThread {
                when (outcome) {
                    Learner.Outcome.ADDED -> toast("'${act.label}'에 추가: ${rule.label()}")
                    Learner.Outcome.FOUND -> confirmDelete(act, rule)
                    Learner.Outcome.MISSING -> toast("'${rule.label()}'은(는) '${act.label}'에 지정돼 있지 않습니다")
                }
            }
        }
    }

    // ───────── 동작 ─────────

    private fun refresh() {
        tvShizuku.text = shizukuState()
        tvStatus.text = "상태: ${KeymapService.status}"
        val fires = KeymapService.instance?.engine?.fireCount ?: 0L
        tvLive.text = "수신 이벤트 ${KeymapService.eventCount} · 동작 실행 $fires\n마지막: ${KeymapService.lastEvent}"
        tvDevices.text = "감지 장치: " + (if (KeymapService.devices.isEmpty()) "없음" else KeymapService.devices)

        if (renderedVersion != Cfg.mapVersion) {
            renderedVersion = Cfg.mapVersion
            renderTable()
        }
        val a = Learner.act
        if (Learner.active && a != null) {
            tvLearn.text = if (Learner.deleteMode) {
                "👉 '${a.label}'에서 삭제할 입력을 지금 JX-11에서 하세요… (터치하면 취소)"
            } else {
                "👉 '${a.label}'에 쓸 입력을 지금 JX-11에서 하세요… (터치하면 취소)"
            }
            tvLearn.visibility = View.VISIBLE
        } else {
            tvLearn.visibility = View.GONE
        }
        if (swRun.isChecked != Cfg.enabled) {
            updating = true
            swRun.isChecked = Cfg.enabled
            updating = false
        }
    }

    private fun shizukuState(): String {
        return try {
            when {
                !Shizuku.pingBinder() -> "❌ Shizuku 실행 안 됨 (Shizuku 앱에서 시작)"
                Shizuku.isPreV11() -> "❌ Shizuku 버전이 너무 낮음"
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                    "✅ Shizuku 권한 허용 (v${Shizuku.getVersion()})"
                else -> "⚠️ Shizuku 권한 필요"
            }
        } catch (t: Throwable) {
            "❌ Shizuku 확인 실패: ${t.message}"
        }
    }

    private fun requestShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                toast("Shizuku가 실행 중이 아닙니다. Shizuku 앱에서 시작하세요")
                packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { startActivity(it) }
                return
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                toast("이미 허용되어 있습니다")
                KeymapService.instance?.onPermissionChanged()
                return
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                toast("Shizuku 앱 > 이 앱 권한을 '허용'으로 바꿔 주세요")
                return
            }
            Shizuku.requestPermission(100)
        } catch (t: Throwable) {
            Diag.e("UI", "Shizuku 권한 요청 실패", t)
            toast("실패: ${t.message}")
        }
    }

    private fun onRunToggle(on: Boolean) {
        try {
            if (on) {
                Cfg.enabled = true
                Cfg.save()
                KeymapService.start(this)
            } else {
                Cfg.enabled = false
                Cfg.save()
                KeymapService.stop(this)
            }
        } catch (t: Throwable) {
            Diag.e("UI", "서비스 ${if (on) "시작" else "중지"} 실패", t)
            toast("실패: ${t.message}")
        }
    }

    private fun applyFilter() {
        Cfg.deviceFilter = etFilter.text.toString().trim()
        Cfg.save()
        val svc = KeymapService.instance
        if (svc == null) {
            toast("저장됨 (키맵퍼를 켜면 적용)")
        } else {
            svc.restartInput()
            toast("적용됨")
        }
    }

    private fun showDeviceList() {
        val svc = KeymapService.instance
        if (svc == null) {
            toast("먼저 키맵퍼를 켜고 Shizuku에 연결하세요")
            return
        }
        Thread {
            val s = svc.listAllDevices()
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("입력 장치 목록")
                    .setMessage(if (s.isEmpty()) "목록을 가져오지 못했습니다 (로그 확인)" else s)
                    .setPositiveButton("닫기", null)
                    .show()
            }
        }.start()
    }

    // ───────── UI 도우미 ─────────

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun title(t: String) = TextView(this).apply {
        text = t
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (v in views) {
            r.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return r
    }

    private fun sw(name: String, get: () -> Boolean, set: (Boolean) -> Unit): Switch {
        return Switch(this).apply {
            text = name
            textSize = 14f
            setPadding(0, dp(6), 0, dp(6))
            isChecked = get()
            setOnCheckedChangeListener { _, c ->
                set(c)
                Cfg.save()
            }
        }
    }

    private fun slider(name: String, maxP: Int, get: () -> Int, set: (Int) -> Unit, fmt: (Int) -> String): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tv = label("")
        val sb = SeekBar(this)
        sb.max = maxP
        sb.progress = get()
        tv.text = "$name: ${fmt(sb.progress)}"
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tv.text = "$name: ${fmt(p)}"
                if (fromUser) {
                    set(p)
                    Cfg.save()
                }
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        box.addView(tv)
        box.addView(sb)
        return box
    }
}
