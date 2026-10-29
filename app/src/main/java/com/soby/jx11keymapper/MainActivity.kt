package com.soby.jx11keymapper

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
import android.text.TextUtils
import android.widget.Toast
import rikka.shizuku.Shizuku
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private val h = Handler(Looper.getMainLooper())
    private lateinit var tvShizuku: TextView
    private lateinit var tvAdb: TextView
    private lateinit var btnBackend: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLive: TextView
    private lateinit var tvDevices: TextView
    private lateinit var tvLearn: TextView
    private lateinit var tableBox: LinearLayout
    private lateinit var tvPreset: TextView
    private lateinit var ring: RingView
    private lateinit var btnPreset: Button
    private lateinit var partsBox: LinearLayout
    private lateinit var othersBox: LinearLayout
    private lateinit var tvNow: TextView
    private lateinit var btnOther: Button
    private lateinit var presetBox: LinearLayout
    private var tvUsage: TextView? = null
    private lateinit var tvMonitor: TextView
    private lateinit var swRun: Switch
    private lateinit var tvHint: TextView
    private lateinit var dot: View
    private lateinit var etFilter: EditText
    private val tabViews = ArrayList<LinearLayout>()
    private val tabButtons = ArrayList<TextView>()

    private val cAccent = 0xFF3D5AFE.toInt()
    private val cBg = 0xFFF3F5F9.toInt()
    private val cCard = 0xFFFFFFFF.toInt()
    private val cText = 0xFF1F2430.toInt()
    private val cSub = 0xFF6B7280.toInt()
    private val cLine = 0xFFE5E8EF.toInt()
    private val cOk = 0xFF2E7D32.toInt()
    private val cWarn = 0xFFEF6C00.toInt()
    private val cBad = 0xFFC62828.toInt()

    companion object {
        private var lastTab = 0
    }
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

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }

        // ── 고정 헤더: 상태 + 켜기 스위치 + 학습 안내 ──
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(6))
        }
        val statusCard = card()
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dot = View(this).apply { background = oval(cSub) }
        top.addView(dot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(10) })
        tvStatus = TextView(this).apply {
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cText)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        top.addView(tvStatus, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        swRun = Switch(this).apply {
            setOnCheckedChangeListener { _, checked -> if (!updating) onRunToggle(checked) }
        }
        top.addView(swRun)
        statusCard.addView(top)
        tvHint = TextView(this).apply {
            textSize = 13f
            setTextColor(cWarn)
            setPadding(0, dp(6), 0, 0)
            visibility = View.GONE
            setOnClickListener { selectTab(2) }
        }
        statusCard.addView(tvHint)
        tvDevices = TextView(this).apply {
            textSize = 12f
            setTextColor(cSub)
            setPadding(0, dp(8), 0, 0)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        statusCard.addView(tvDevices)
        tvLive = TextView(this).apply {
            textSize = 12f
            setTextColor(cSub)
            setPadding(0, dp(2), 0, 0)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        statusCard.addView(tvLive)
        tvPreset = TextView(this).apply {
            textSize = 13f
            setTextColor(cAccent)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, 0)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        statusCard.addView(tvPreset)
        header.addView(statusCard)
        tvLearn = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(cAccent, 12)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
            setOnClickListener { Learner.cancel() }
        }
        header.addView(tvLearn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        page.addView(header)

        // ── 탭 ──
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(0xFFE4E8F0.toInt(), 12)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val tabNames = arrayOf("JX-11", "프리셋", "연결", "설정", "범용기기")
        for (i in tabNames.indices) {
            val t = TextView(this).apply {
                text = tabNames[i]
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { selectTab(i) }
            }
            tabButtons.add(t)
            tabBar.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        page.addView(tabBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(16); rightMargin = dp(16); topMargin = dp(4) })

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(28))
        }
        for (i in 0 until 5) {
            val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            tabViews.add(v)
            content.addView(v)
        }
        page.addView(ScrollView(this).apply { addView(content) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        buildRingTab(tabViews[0])
        presetBox = tabViews[1]
        buildConnectionTab(tabViews[2])
        buildSettingsTab(tabViews[3])
        buildGenericTab(tabViews[4])
        selectTab(lastTab)

        setContentView(page)
    }

    private fun selectTab(i: Int) {
        lastTab = i
        for (k in tabViews.indices) {
            tabViews[k].visibility = if (k == i) View.VISIBLE else View.GONE
            val sel = k == i
            tabButtons[k].setTextColor(if (sel) 0xFFFFFFFF.toInt() else cSub)
            tabButtons[k].setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
            tabButtons[k].background = if (sel) rounded(cAccent, 9) else null
        }
    }

    // ───────── 탭 내용 ─────────

    // ───────── JX-11 탭 ─────────

    private fun buildRingTab(box: LinearLayout) {
        box.addView(sectionTitle("편집 중인 프리셋"))
        btnPreset = pill("", 1) { choosePresetToEdit() }
        box.addView(btnPreset, fullWidth())

        box.addView(sectionTitle("JX-11"))
        box.addView(note("그림에서 부위를 누르면 기능을 고를 수 있어요. JX-11을 직접 조작하면 눌린 부위가 주황색으로 칠해지고, 휠은 실제로 굴린 방향 화살표가 나와요. (파란색 = 이미 지정됨)"))
        val rc = card()
        ring = RingView(this)
        ring.onPart = { p -> pickActForRules("${p.title}  (${p.hint})", p.rules()) }
        rc.addView(ring, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        tvNow = TextView(this).apply {
            textSize = 13f
            setTextColor(cSub)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(2))
        }
        rc.addView(tvNow)
        box.addView(rc)

        box.addView(sectionTitle("부위별 기능"))
        val pc = card()
        partsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pc.addView(partsBox)
        box.addView(pc)

        btnOther = pill("", 2) {
            val r = RingEvents.lastOther
            if (r != null) pickActForRules("그림에 없는 입력: ${r.label()}", listOf(r))
        }
        btnOther.visibility = View.GONE
        box.addView(btnOther, fullWidth())

        othersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(othersBox)

        box.addView(pill("기능 먼저 고르고 → JX-11을 눌러서 지정", 1) { pickActToAdd() }, fullWidth())
    }

    private fun renderRing() {
        val e = Cfg.editing
        btnPreset.text = if (e == null) "프리셋 없음 — [프리셋] 탭에서 만들어요"
        else "프리셋: ${e.name}" + (if (e.id == Cfg.runtimeId) "  · 적용 중" else "") + "   (눌러서 바꾸기)"

        val assigned = HashSet<Part>()
        partsBox.removeAllViews()
        othersBox.removeAllViews()
        if (e == null) {
            ring.assigned = emptySet()
            ring.invalidate()
            partsBox.addView(TextView(this).apply {
                text = "프리셋이 없어요. [프리셋] 탭에서 새 프리셋을 만드세요."
                textSize = 14f
                setTextColor(cSub)
                setPadding(dp(4), dp(12), dp(4), dp(12))
            })
            return
        }
        val parts = Part.values()
        parts.forEachIndexed { i, p ->
            val act = Act.values().firstOrNull { a -> p.rules().any { r -> r in e.rules(a) } }
            if (act != null) assigned.add(p)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { pickActForRules("${p.title}  (${p.hint})", p.rules()) }
            }
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            left.addView(TextView(this).apply {
                text = p.title
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(cText)
            })
            left.addView(TextView(this).apply {
                text = "입력: ${p.hint}"
                textSize = 12f
                setTextColor(cSub)
            })
            row.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(
                if (act != null) chip(act.label) else TextView(this).apply {
                    text = "미지정"
                    textSize = 13f
                    setTextColor(cSub)
                }
            )
            partsBox.addView(row)
            if (i < parts.size - 1) divider(partsBox)
        }
        ring.assigned = assigned
        ring.invalidate()

        // 그림에 없는 입력으로 지정된 것들
        val extras = ArrayList<Pair<Act, Rule>>()
        for (a in Act.values()) for (r in e.rules(a)) if (Part.of(r) == null) extras.add(a to r)
        if (extras.isNotEmpty()) {
            othersBox.addView(sectionTitle("그림에 없는 입력"))
            val c = card()
            extras.forEachIndexed { i, (a, r) ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, dp(8))
                }
                row.addView(TextView(this).apply {
                    text = "${r.label()}  →  ${a.label}"
                    textSize = 14f
                    setTextColor(cText)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(roundIcon("－", cBad) { Cfg.removeRule(a, r) },
                    LinearLayout.LayoutParams(dp(40), dp(40)))
                c.addView(row)
                if (i < extras.size - 1) divider(c)
            }
            othersBox.addView(c)
        }
    }

    /** 이 부위(입력 여러 개일 수 있음)에 연결할 기능을 목록에서 고른다. 같은 입력은 한 기능에만 지정돼요 */
    private fun pickActForRules(title: String, rules: List<Rule>) {
        val e = Cfg.editing
        if (e == null) {
            toast("프리셋이 없어요. [프리셋] 탭에서 만드세요")
            return
        }
        val acts = Act.values()
        val cur = acts.firstOrNull { a -> rules.any { it in e.rules(a) } }
        val names = arrayOf("지정 안 함") + acts.map { (if (it == cur) "✓ " else "") + it.label }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(names) { _, which ->
                if (which == 0) {
                    for (r in rules) Cfg.removeRuleEverywhere(r)
                    Diag.i("UI", "지정 해제: ${rules.joinToString { it.label() }}")
                    toast("지정 해제")
                } else {
                    val a = acts[which - 1]
                    for (r in rules) Cfg.addRule(a, r)
                    Diag.i("UI", "지정: ${rules.joinToString { it.label() }} → ${a.label} (프리셋 ${e.name})")
                    toast("'${a.label}'에 지정")
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun choosePresetToEdit() {
        val ps = Cfg.presets
        if (ps.isEmpty()) {
            selectTab(1)
            return
        }
        val names = ps.map { it.name + (if (it.id == Cfg.runtimeId) "  (적용 중)" else "") }.toTypedArray()
        val cur = ps.indexOfFirst { it.id == Cfg.editing?.id }
        AlertDialog.Builder(this)
            .setTitle("편집할 프리셋")
            .setSingleChoiceItems(names, cur) { d, which ->
                Cfg.selectEdit(ps[which].id)
                d.dismiss()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    // ───────── 프리셋 탭 ─────────

    private fun renderPresetTab() {
        val box = presetBox
        box.removeAllViews()
        tvUsage = null

        box.addView(sectionTitle("프리셋"))
        box.addView(note("프리셋마다 키 지정과 감도를 따로 가져요. 앱을 연결해 두면 그 앱이 화면에 있을 때 자동으로 바뀌어요. 눌러서 편집할 프리셋을 고르세요."))
        val lc = card()
        val ps = Cfg.presets
        if (ps.isEmpty()) {
            lc.addView(TextView(this).apply {
                text = "프리셋이 없어요. 아래 [＋ 새 프리셋]을 눌러 만드세요."
                textSize = 14f
                setTextColor(cSub)
                setPadding(dp(4), dp(12), dp(4), dp(12))
            })
        }
        ps.forEachIndexed { i, p ->
            val editing = p.id == Cfg.editing?.id
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { Cfg.selectEdit(p.id) }
            }
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            left.addView(TextView(this).apply {
                text = (if (editing) "✓ " else "") + p.name
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (editing) cAccent else cText)
            })
            val sub = ArrayList<String>()
            sub.add(if (p.apps.isEmpty()) "연결된 앱 없음" else "앱 ${p.apps.size}개 연결")
            if (p.fallback) sub.add("나머지 모든 앱")
            val n = p.map.values.sumOf { it.size }
            sub.add("지정 ${n}개")
            left.addView(TextView(this).apply {
                text = sub.joinToString(" · ")
                textSize = 12f
                setTextColor(cSub)
            })
            row.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (p.id == Cfg.runtimeId) row.addView(chip("적용 중"))
            lc.addView(row)
            if (i < ps.size - 1) divider(lc)
        }
        box.addView(lc)
        box.addView(row(
            pill("＋ 새 프리셋", 0) {
                askName("새 프리셋 이름", "프리셋 ${Cfg.presets.size + 1}") { n ->
                    Cfg.newPreset(n)
                    toast("'$n' 만들었어요")
                }
            },
            pill("이름 변경", 1) {
                val e = Cfg.editing
                if (e == null) toast("프리셋이 없어요") else askName("이름 변경", e.name) { n -> Cfg.renamePreset(e.id, n) }
            },
            pill("삭제", 2) { deleteEditingPreset() }
        ))

        val e = Cfg.editing ?: return

        box.addView(sectionTitle("'${e.name}' 적용 대상"))
        val ac = card()
        val appText = if (e.apps.isEmpty()) "연결된 앱 없음" else e.apps.joinToString(", ") { appLabel(it) }
        ac.addView(TextView(this).apply {
            text = appText
            textSize = 14f
            setTextColor(cText)
            setPadding(0, dp(8), 0, dp(8))
        })
        box.addView(ac)
        box.addView(pill("앱 연결 편집", 0) { editApps(e) }, fullWidth())
        val fc = card()
        switchRowRaw(fc, "연결되지 않은 모든 앱에 적용", "다른 프리셋에 연결되지 않은 앱에서는 이 프리셋을 써요 (하나의 프리셋만 가능)", e.fallback) { on ->
            Cfg.setFallback(e.id, on)
        }
        divider(fc)
        tvUsage = TextView(this).apply {
            textSize = 12f
            setTextColor(cSub)
            setPadding(0, dp(10), 0, dp(6))
        }
        fc.addView(tvUsage)
        box.addView(fc, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        box.addView(sectionTitle("'${e.name}' 스크롤 방식"))
        box.addView(note("JX-11 휠(세로 이동)을 어떻게 처리할지 프리셋마다 정해요. 웹툰·웹페이지는 스와이프나 마우스 휠, 이북 페이지 넘기기는 키 변환이 잘 맞아요."))
        val mc = card()
        val modeNames = arrayOf("키 변환", "스와이프", "마우스 휠")
        mc.addView(row(
            *Array(3) { i ->
                pill(modeNames[i], if (e.scroll == i) 0 else 1) {
                    if (e.scroll != i) Cfg.updateEditing { it.copy(scroll = i) }
                }
            }
        ))
        mc.addView(TextView(this).apply {
            text = when (e.scroll) {
                1 -> "휠을 굴린 만큼 가상 손가락이 화면을 끌어요. 어떤 앱에서든 동작하고 손 뗄 때 관성도 생겨요. 이 방식에서는 키 지정표의 휠(스와이프 ↑↓) 항목은 쓰이지 않아요. ◁ ▷ 키, 원형 키(탭), 타원 키는 지정한 대로 동작해요."
                2 -> "휠을 굴린 만큼 마우스 휠 스크롤을 보내요. 브라우저·웹뷰처럼 마우스 휠을 받는 앱에서 부드러워요. 일부 전용 뷰어 앱은 무시할 수 있어요. 키 지정표의 휠(스와이프 ↑↓) 항목은 쓰이지 않아요."
                else -> "휠을 이만큼 움직일 때마다 키(볼륨·방향키·페이지)를 눌러요. 아래 슬라이더로 반응 간격을 조절해요."
            }
            textSize = 12f
            setTextColor(cSub)
            setPadding(dp(2), dp(10), dp(2), dp(6))
        })
        box.addView(mc)

        box.addView(sectionTitle("'${e.name}' 감도"))
        val c = card()
        if (e.scroll == 0) {
            sliderRow(c, "이동 감도", "휠을 이만큼 움직일 때마다 동작 1회. 작을수록 민감해요. (휠 한 칸 ≈ 150~180‰)", 29,
                { ((e.unit / 10) - 1).coerceIn(0, 29) },
                { v -> Cfg.updateEditing(false) { it.copy(unit = (v + 1) * 10) } },
                { "${(it + 1) * 10}‰" })
            divider(c)
            sliderRow(c, "연속 동작 간격", "연속 동작 사이의 최소 간격. 작을수록 빠르게 반복돼요.", 50,
                { (e.cooldown / 10).coerceIn(0, 50) },
                { v -> Cfg.updateEditing(false) { it.copy(cooldown = v * 10) } },
                { "${it * 10}ms" })
            divider(c)
            sliderRow(c, "동작 1회당 반복 횟수", "한 번 동작할 때 볼륨·방향키·페이지 키를 몇 번 누를지", 49,
                { (e.steps - 1).coerceIn(0, 49) },
                { v -> Cfg.updateEditing(false) { it.copy(steps = v + 1) } },
                { "${it + 1}번" })
        } else {
            val title = if (e.scroll == 1) "이동 배율" else "칸당 이동량 배율"
            val desc = if (e.scroll == 1) "휠을 굴린 만큼 화면이 움직이는 비율. 100% = 기본 휠 동작과 같은 이동량, 클수록 많이 움직여요."
                else "휠 한 칸당 스크롤 양의 배율. 100% = 일반 마우스 휠 1칸, 클수록 많이 움직여요."
            sliderRow(c, title, desc, 19,
                { ((e.gain / 25) - 1).coerceIn(0, 19) },
                { v -> Cfg.updateEditing(false) { it.copy(gain = (v + 1) * 25) } },
                { "${(it + 1) * 25}%" })
            if (e.scroll == 1) {
                divider(c)
                switchRowRaw(c, "손 뗄 때 관성", "끄면 휠을 멈추는 즉시 화면도 멈춰요. 켜면 굴리던 속도만큼 더 미끄러져요.", e.fling) { on ->
                    Cfg.updateEditing(false) { it.copy(fling = on) }
                }
            }
        }
        box.addView(c)
        if (e.scroll == 0) {
            box.addView(pill("볼륨 테스트 ↑↓", 1) {
                VolumeAction.apply(this, true, 1)
                h.postDelayed({ VolumeAction.apply(this, false, 1) }, 600)
            }, fullWidth())
        } else {
            box.addView(note("배율은 바꾸는 즉시 적용돼요. 앞 앱에 연결된 프리셋일 때만 실제로 쓰여요(지금 적용 중인 프리셋은 위 '적용 중' 표시 참고)."))
        }
    }

    private fun deleteEditingPreset() {
        val e = Cfg.editing
        if (e == null) {
            toast("프리셋이 없어요")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("'${e.name}' 삭제")
            .setMessage("이 프리셋의 키 지정, 감도, 앱 연결이 모두 사라져요.")
            .setPositiveButton("삭제") { _, _ ->
                Cfg.deletePreset(e.id)
                Diag.i("UI", "프리셋 삭제: ${e.name}")
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun askName(title: String, init: String, ok: (String) -> Unit) {
        val et = EditText(this).apply {
            setSingleLine()
            setText(init)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(et)
            .setPositiveButton("확인") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty()) ok(n)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private val labelCache = HashMap<String, String>()

    private fun appLabel(pkg: String): String = labelCache.getOrPut(pkg) {
        try {
            packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
        } catch (t: Throwable) {
            pkg
        }
    }

    /** 실행 가능한 앱 목록에서 이 프리셋에 연결할 앱을 체크 */
    private fun editApps(e: Preset) {
        toast("앱 목록 불러오는 중…")
        Thread {
            val list: List<Pair<String, String>> = try {
                val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val found = packageManager.queryIntentActivities(i, 0)
                    .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
                    .filter { it.first != packageName }
                    .toMutableList()
                for (p in e.apps) if (found.none { it.first == p }) found.add(p to p)
                found.distinctBy { it.first }.sortedBy { it.second.lowercase() }
            } catch (t: Throwable) {
                Diag.e("UI", "앱 목록 실패", t)
                emptyList()
            }
            runOnUiThread {
                if (list.isEmpty()) {
                    toast("앱 목록을 가져오지 못했어요 (로그 확인)")
                    return@runOnUiThread
                }
                val others = Cfg.presets.filter { it.id != e.id }
                val labels = list.map { (pkg, name) ->
                    val o = others.firstOrNull { pkg in it.apps }
                    if (o != null) "$name  · ${o.name}" else name
                }.toTypedArray()
                val checked = BooleanArray(list.size) { list[it].first in e.apps }
                AlertDialog.Builder(this)
                    .setTitle("'${e.name}'에 연결할 앱")
                    .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                    .setPositiveButton("적용") { _, _ ->
                        val sel = list.indices.filter { checked[it] }.map { list[it].first }
                        Cfg.setApps(e.id, sel)
                        Diag.i("UI", "앱 연결: ${e.name} ← ${sel.size}개")
                        toast("앱 ${sel.size}개 연결 (다른 프리셋에 있던 앱은 이쪽으로 옮겨졌어요)")
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
        }.start()
    }

    // ───────── 범용기기 탭 ─────────

    private fun buildGenericTab(box: LinearLayout) {
        box.addView(sectionTitle("입력 장치"))
        val c4 = card()
        c4.addView(note("장치 이름에 이 글자가 들어 있으면 읽어요 (비우면 전체). JX-11 외의 기기도 여기서 확인할 수 있어요."))
        etFilter = EditText(this).apply {
            setSingleLine()
            setText(Cfg.deviceFilter)
            hint = "예: JX"
        }
        c4.addView(etFilter)
        box.addView(c4)
        box.addView(row(
            pill("적용", 0) { applyFilter() },
            pill("장치 목록", 1) { showDeviceList() }
        ))

        box.addView(sectionTitle("입력 모니터 (원본 값)"))
        box.addView(note("수신한 입력 원본이 최근 것부터 보여요 (프리셋·지정과 무관). 새로운 키의 코드를 알아낼 때 쓰세요."))
        val mc = card()
        tvMonitor = TextView(this).apply {
            textSize = 11f
            setTextColor(cText)
            typeface = Typeface.MONOSPACE
            minLines = 6
        }
        mc.addView(tvMonitor)
        box.addView(mc)
        box.addView(pill("지우기", 1) { EventMonitor.clear(); tvMonitor.text = "" }, fullWidth())

        // 범용기기 탭은 프리셋과 무관하게 입력 원본만 보여준다 (지정은 JX-11 탭에서)
        tableBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }   // 화면에 붙이지 않음
    }

    /** 제목 + 설명 + 스위치 (값은 즉시 호출, 저장은 호출한 쪽에서) */
    private fun switchRowRaw(parent: LinearLayout, name: String, desc: String, on: Boolean, set: (Boolean) -> Unit) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply { text = name; textSize = 15f; setTextColor(cText) })
        texts.addView(TextView(this).apply {
            text = desc
            textSize = 12f
            setTextColor(cSub)
            setPadding(0, dp(2), 0, 0)
        })
        r.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(10) })
        r.addView(Switch(this).apply {
            isChecked = on
            setOnCheckedChangeListener { _, c -> set(c) }
        })
        parent.addView(r)
    }

    private fun buildConnectionTab(box: LinearLayout) {
        box.addView(sectionTitle("연결 상태"))
        val st = card()
        tvShizuku = TextView(this).apply { textSize = 14f; setTextColor(cText) }
        tvAdb = TextView(this).apply {
            textSize = 14f
            setTextColor(cText)
            setPadding(0, dp(8), 0, 0)
        }
        st.addView(tvShizuku)
        st.addView(tvAdb)
        box.addView(st)

        box.addView(sectionTitle("연결 방식"))
        box.addView(note("입력을 읽으려면 shell 권한이 필요해요. Shizuku 또는 무선 ADB(앱이 직접 페어링) 중 어느 쪽으로 얻을지 고릅니다."))
        btnBackend = pill("", 1) { chooseBackend() }
        box.addView(btnBackend, fullWidth())

        box.addView(sectionTitle("무선 ADB"))
        box.addView(note("① 아래 버튼을 누르면 무선 디버깅 설정이 열려요  ② 무선 디버깅을 켜고 '페어링 코드로 기기 페어링'을 누르세요  ③ 상단바 알림의 입력창에 6자리 코드를 입력하면 끝! 한 번 연결하면 재부팅 전까지 유지돼요."))
        box.addView(pill("무선 ADB 연결(페어링)", 0) { startAdbPairing() }, fullWidth())
        box.addView(pill("Shizuku 권한 요청", 1) { requestShizuku() }, fullWidth())
        box.addView(pill("ADB 서버 종료", 2) { killAdb() }, fullWidth())
    }

    private fun buildSettingsTab(box: LinearLayout) {
        box.addView(sectionTitle("입력"))
        val c1 = card()
        switchRow(c1, "JX-11 독점", "원래 터치/스크롤 동작을 막아요", { Cfg.grab }) {
            Cfg.grab = it
            Cfg.save()
            KeymapService.instance?.restartInput()
        }
        divider(c1)
        switchRow(c1, "연결 중에도 화면 키보드 표시", "JX-11이 키보드로 인식돼 화면 키보드가 숨겨지는 걸 막아요", { Cfg.imeWithKeyboard }) {
            Cfg.imeWithKeyboard = it
            Cfg.save()
            KeymapService.instance?.applyIme()
        }
        box.addView(c1)

        box.addView(sectionTitle("볼륨"))
        val c2 = card()
        switchRow(c2, "볼륨을 키 입력으로 보내기", "이북 앱 페이지 넘김에 필요해요", { Cfg.volumeKey }) { Cfg.volumeKey = it }
        divider(c2)
        switchRow(c2, "미디어 볼륨만 조절", "켜면 키 입력 대신 미디어 볼륨을 직접 조절해요 (이북 페이지 넘김은 안 돼요)", { Cfg.mediaFixed }) { Cfg.mediaFixed = it }
        divider(c2)
        switchRow(c2, "볼륨 UI 표시", "볼륨이 바뀔 때 화면에 볼륨 바를 보여줘요", { Cfg.showUi }) { Cfg.showUi = it }
        box.addView(c2)

        box.addView(sectionTitle("E-ink 새로고침"))
        val ce = card()
        ce.addView(note("전체 화면을 완전한 검정→흰색으로 잠깐 덮어 잔상을 지워요. 기능 목록의 'E-ink 화면 새로고침'에 JX-11 키를 지정해 쓰세요. '다른 앱 위에 표시' 권한이 필요하고, 셸 연결 때 자동 허용돼요. 시간 기본값은 근거 없이 정한 값이라 기기에서 맞춰 보세요."))
        sliderRow(ce, "검정 유지 시간", "검정 화면을 보여주는 시간", 19,
            { (Cfg.einkBlackMs / 100 - 1).coerceIn(0, 19) },
            { Cfg.einkBlackMs = (it + 1) * 100 },
            { "${(it + 1) * 100}ms" })
        divider(ce)
        sliderRow(ce, "흰색 유지 시간", "흰 화면을 보여주는 시간", 19,
            { (Cfg.einkWhiteMs / 100 - 1).coerceIn(0, 19) },
            { Cfg.einkWhiteMs = (it + 1) * 100 },
            { "${(it + 1) * 100}ms" })
        divider(ce)
        sliderRow(ce, "반복 횟수", "검정↔흰색을 몇 번 반복할지", 4,
            { (Cfg.einkRepeat - 1).coerceIn(0, 4) },
            { Cfg.einkRepeat = it + 1 },
            { "${it + 1}번" })
        box.addView(ce)
        box.addView(pill("새로고침 테스트", 1) { EinkRefresh.test(this) }, fullWidth())

        box.addView(sectionTitle("시스템"))
        val c3 = card()
        switchRow(c3, "화면이 꺼져도 동작", "배터리를 더 사용해요", { Cfg.wakeLock }) {
            Cfg.wakeLock = it
            KeymapService.instance?.applyWake()
        }
        box.addView(c3)

        box.addView(sectionTitle("도구"))
        box.addView(pill("로그 보기", 1) { startActivity(Intent(this, LogActivity::class.java)) }, fullWidth())
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
        val mapped = Act.values().filter { Cfg.rulesOf(it).isNotEmpty() }
        if (mapped.isEmpty()) {
            tableBox.addView(TextView(this).apply {
                text = "아직 지정된 입력이 없어요.\n아래 [＋ 기능 추가]를 눌러 시작하세요."
                textSize = 14f
                setTextColor(cSub)
                setPadding(dp(4), dp(14), dp(4), dp(14))
            })
            return
        }
        mapped.forEachIndexed { i, act ->
            val rules = Cfg.rulesOf(act)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            left.addView(TextView(this).apply {
                text = act.label
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(cText)
            })
            for (r in rules) {
                left.addView(chip(r.label()), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) })
            }
            row.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(roundIcon("＋", cAccent) { startLearn(act, false) },
                LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(8) })
            row.addView(roundIcon("－", cBad) { onMinus(act, rules) },
                LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(8) })
            tableBox.addView(row)
            if (i < mapped.size - 1) divider(tableBox)
        }
    }

    /** 아직 입력이 없는 기능 중 하나를 골라 바로 입력 등록으로 */
    private fun pickActToAdd() {
        val unmapped = Act.values().filter { Cfg.rulesOf(it).isEmpty() }
        if (unmapped.isEmpty()) {
            toast("모든 기능에 입력이 있어요. 목록의 ＋로 더 추가하세요")
            return
        }
        val names = unmapped.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("어떤 기능에 연결할까요?")
            .setItems(names) { _, which -> startLearn(unmapped[which], false) }
            .setNegativeButton("취소", null)
            .show()
    }

    /** － : 이 기능에 지정된 입력 목록을 체크박스로 보여주고, 체크를 해제한 것을 삭제한다 (키 입력 필요 없음) */
    private fun onMinus(act: Act, rules: List<Rule>) {
        if (rules.isEmpty()) return
        val labels = rules.map { it.label() }.toTypedArray()
        val checked = BooleanArray(rules.size) { true }
        AlertDialog.Builder(this)
            .setTitle("'${act.label}' 지정 목록")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("적용") { _, _ ->
                var n = 0
                for (i in rules.indices) {
                    if (!checked[i]) {
                        Cfg.removeRule(act, rules[i])
                        Diag.i("UI", "삭제: ${act.label} ← ${rules[i].label()}")
                        n++
                    }
                }
                toast(if (n > 0) "${n}개 삭제됨" else "변경 없음")
            }
            .setNegativeButton("취소", null)
            .show()
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
        btnBackend.text = "연결 방식: " + backendName(Cfg.backend) + "  (눌러서 변경)"
        tvAdb.text = "무선 ADB: " + (if (AdbPairingService.running) AdbPairingService.state
            else if (Cfg.adbPort != 0) "서버 정보 저장됨 (포트 ${Cfg.adbPort}) · ${AdbPairingService.state}"
            else "서버 없음 · ${AdbPairingService.state}")
        val st = KeymapService.status
        tvStatus.text = if (Cfg.enabled) st else "꺼짐 · 오른쪽 스위치로 켜세요"
        val live = Cfg.enabled && st.startsWith("동작 중")
        val bad = Cfg.enabled && !live && (st.contains("끊김") || st.contains("없음") || st.contains("실패") || st.contains("안 됨"))
        (dot.background as? GradientDrawable)?.setColor(
            if (live) cOk else if (bad) cBad else if (Cfg.enabled) cWarn else cSub
        )
        if (Cfg.enabled && !live) {
            tvHint.text = "연결이 필요해요 → [연결] 탭에서 무선 ADB나 Shizuku를 설정하세요 (눌러서 이동)"
            tvHint.visibility = View.VISIBLE
        } else {
            tvHint.visibility = View.GONE
        }
        val fires = KeymapService.instance?.engine?.fireCount ?: 0L
        tvLive.text = "수신 ${KeymapService.eventCount} · 동작 $fires · 마지막: ${KeymapService.lastEvent}"
        tvDevices.text = "감지 장치: " + (if (KeymapService.devices.isEmpty()) "없음" else KeymapService.devices)

        val key = Cfg.mapVersion * 31 + Cfg.runtimeId.hashCode()
        if (renderedVersion != key) {
            renderedVersion = key
            renderRing()
            renderPresetTab()
            renderTable()
        }
        val rp = Cfg.presets.firstOrNull { it.id == Cfg.runtimeId }
        val ed = Cfg.editing
        tvPreset.text = "적용 중: ${rp?.name ?: "없음"}" +
            (if (Cfg.runtimePkg.isNotEmpty() && Cfg.presets.any { it.apps.isNotEmpty() }) " · 앞 앱 ${appLabel(Cfg.runtimePkg)}" else "") +
            (if (ed != null && ed.id != rp?.id) "   (편집 중: ${ed.name})" else "")
        val lp = RingEvents.lastPart
        tvNow.text = if (lp != null && android.os.SystemClock.elapsedRealtime() - RingEvents.lastTime < 4000)
            "방금: ${lp.title}  (${lp.hint})" else "JX-11을 조작해 보세요"
        val ro = RingEvents.lastOther
        val edp = Cfg.editing
        if (ro != null && edp != null && Act.values().none { ro in edp.rules(it) }) {
            btnOther.text = "그림에 없는 입력 감지: ${ro.label()} — 눌러서 지정"
            btnOther.visibility = View.VISIBLE
        } else {
            btnOther.visibility = View.GONE
        }
        tvUsage?.text = if (KeymapService.usageOk) "✅ 앞 앱 감지 가능 (사용 기록 접근이 자동 허용됨)"
            else "⚠ 앞 앱 감지 권한 없음 — 키맵퍼를 켜고 셸(무선 ADB/Shizuku)에 연결하면 자동으로 허용돼요. 그 전에는 '나머지 모든 앱' 프리셋만 적용돼요."
        if (lastTab == 4) tvMonitor.text = EventMonitor.snapshot(25)
        val a = Learner.act
        if (Learner.active && a != null) {
            tvLearn.text = if (Learner.deleteMode) {
                "👉 '${a.label}'에서 삭제할 입력을 지금 JX-11에서 하세요 (눌러서 취소)"
            } else {
                "👉 '${a.label}'에 쓸 입력을 지금 JX-11에서 하세요 (눌러서 취소)"
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

    private fun backendName(b: Int) = when (b) {
        KeymapService.BACKEND_SHIZUKU -> "Shizuku만"
        KeymapService.BACKEND_ADB -> "무선 ADB만"
        else -> "자동 (Shizuku 우선, 없으면 무선 ADB)"
    }

    private fun chooseBackend() {
        val names = arrayOf(backendName(0), backendName(1), backendName(2))
        AlertDialog.Builder(this)
            .setTitle("연결 방식")
            .setSingleChoiceItems(names, Cfg.backend) { d, which ->
                Cfg.backend = which
                Cfg.save()
                Diag.i("UI", "연결 방식 변경: ${names[which]}")
                d.dismiss()
                if (Cfg.enabled) {
                    toast("서비스를 다시 시작합니다")
                    KeymapService.stop(this)
                    h.postDelayed({ KeymapService.start(this) }, 700)
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    /** 버튼 한 번: 알림 권한 확인 → 무선 디버깅 화면 열기 + 연결 마법사(알림에 코드 입력창) 시작 */
    private fun startAdbPairing() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Diag.i("UI", "알림 권한 요청 (무선 ADB 용)")
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7001)
            return
        }
        Diag.i("UI", "무선 ADB 연결 버튼 · 무선 디버깅 설정 열기 + 서비스 시작")
        try {
            AdbPairingService.start(this)
        } catch (t: Throwable) {
            Diag.e("UI", "무선 ADB 마법사 시작 실패", t)
            toast("시작 실패: ${t.message}")
            return
        }
        openWirelessDebugging()
        toast("무선 디버깅을 켜고 '페어링 코드로 기기 페어링'을 누르면, 상단바 알림에 코드 입력창이 떠요")
    }

    @Deprecated("Deprecated in Activity")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        Diag.i("UI", "권한 요청 결과 code=$requestCode 허용=$ok")
        if (requestCode == 7001 && ok) startAdbPairing()
    }

    /** 개발자 옵션을 열고 '무선 디버깅' 항목으로 바로 스크롤·강조 (Shizuku 와 같은 방식). 실패하면 개발자 옵션만 연다 */
    private fun openWirelessDebugging() {
        val key = "toggle_adb_wireless"
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .putExtra(":settings:fragment_args_key", key)
                .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", key) })
            startActivity(intent)
            Diag.i("UI", "무선 디버깅 항목으로 이동")
        } catch (t: Throwable) {
            Diag.w("UI", "무선 디버깅 바로가기 실패 → 개발자 옵션만 열기: ${t.message}")
            try {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (t2: Throwable) {
                Diag.w("UI", "개발자 옵션 열기 실패: ${t2.message}")
            }
        }
    }

    private fun killAdb() {
        Thread {
            try {
                AdbBridge.quitServer()
                runOnUiThread { toast("서버 종료 요청을 보냈습니다") }
            } catch (t: Throwable) {
                Diag.e("UI", "서버 종료 실패", t)
            }
        }.start()
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

    private fun rounded(color: Int, radiusDp: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun oval(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(cCard, 14, cLine)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
    }

    private fun divider(parent: LinearLayout) {
        parent.addView(View(this).apply { setBackgroundColor(cLine) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(cSub)
        setPadding(dp(4), dp(10), 0, dp(6))
    }

    private fun note(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(cSub)
        setPadding(dp(4), 0, dp(4), dp(8))
    }

    private fun fullWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(6) }

    /** kind: 0=주요(채움), 1=보조(연한 채움), 2=위험(빨간 글씨) */
    private fun pill(t: String, kind: Int, onClick: () -> Unit): Button {
        val bg = when (kind) {
            0 -> cAccent
            2 -> 0xFFFDECEC.toInt()
            else -> 0xFFE4E8F0.toInt()
        }
        val fg = when (kind) {
            0 -> 0xFFFFFFFF.toInt()
            2 -> cBad
            else -> cText
        }
        return Button(this).apply {
            text = t
            textSize = 15f
            setAllCaps(false)
            setTextColor(fg)
            stateListAnimator = null
            minHeight = dp(48)
            minimumHeight = dp(48)
            background = RippleDrawable(ColorStateList.valueOf(0x22000000), rounded(bg, 12), null)
            setOnClickListener { onClick() }
        }
    }

    private fun roundIcon(t: String, color: Int, onClick: () -> Unit): Button = Button(this).apply {
        text = t
        textSize = 20f
        setAllCaps(false)
        setTextColor(color)
        stateListAnimator = null
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
        background = RippleDrawable(ColorStateList.valueOf(0x22000000), oval(0xFFEEF1F7.toInt()), null)
        setOnClickListener { onClick() }
    }

    private fun chip(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(cAccent)
        background = rounded(0xFFE8EDFF.toInt(), 8)
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }

    private fun row(vararg views: View): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, v) in views.withIndex()) {
            r.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                topMargin = dp(6)
                if (i > 0) leftMargin = dp(8)
            })
        }
        return r
    }

    /** 제목 + 설명 + 오른쪽 스위치 한 줄 */
    private fun switchRow(parent: LinearLayout, name: String, desc: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = name
            textSize = 15f
            setTextColor(cText)
        })
        texts.addView(TextView(this).apply {
            text = desc
            textSize = 12f
            setTextColor(cSub)
            setPadding(0, dp(2), 0, 0)
        })
        r.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(10) })
        r.addView(Switch(this).apply {
            isChecked = get()
            setOnCheckedChangeListener { _, c ->
                set(c)
                Cfg.save()
            }
        })
        parent.addView(r)
    }

    /** 제목 + 현재값(오른쪽) + 슬라이더 + 설명 */
    private fun sliderRow(
        parent: LinearLayout, name: String, desc: String, maxP: Int,
        get: () -> Int, set: (Int) -> Unit, fmt: (Int) -> String
    ) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(10))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = name
            textSize = 15f
            setTextColor(cText)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val value = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cAccent)
        }
        head.addView(value)
        box.addView(head)
        val sb = SeekBar(this)
        sb.max = maxP
        sb.progress = get()
        value.text = fmt(sb.progress)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                value.text = fmt(p)
                if (fromUser) {
                    set(p)
                    Cfg.save()
                }
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        box.addView(sb)
        box.addView(TextView(this).apply {
            text = desc
            textSize = 12f
            setTextColor(cSub)
        })
        parent.addView(box)
    }
}
