package com.soby.jx11keymapper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/**
 * JX-11 의 조작 부위. 입력 종류는 기기에서 실제로 관찰한 값 기준:
 * 휠을 위로 굴리면 스와이프 ↓, 아래로 굴리면 스와이프 ↑ (물리 방향과 스와이프 방향이 반대),
 * ">" 키 = 스와이프 ←, "<" 키 = 스와이프 →, 원형 키 = 탭, 타원 키 = 볼륨 내림 키(EV_KEY 114, 추정).
 */
enum class Part(val title: String, val hint: String, val type: Int, val code: Int) {
    WHEEL_UP("휠 위로 굴림", "스와이프 ↓", EventNames.EV_GESTURE, 2),
    WHEEL_DOWN("휠 아래로 굴림", "스와이프 ↑", EventNames.EV_GESTURE, 1),
    KEY_LEFT("◁ 키", "스와이프 →", EventNames.EV_GESTURE, 4),
    KEY_RIGHT("▷ 키", "스와이프 ←", EventNames.EV_GESTURE, 3),
    OVAL("타원 키", "볼륨 내림 키 (114)", EventNames.EV_KEY, 114),
    CIRCLE("원형 키", "탭", EventNames.EV_GESTURE, 5);

    fun rule(): Rule = Rule(type, code, 0)

    companion object {
        fun of(r: Rule): Part? = values().firstOrNull { it.type == r.type && it.code == r.code }

        fun fromEvent(type: Int, code: Int, value: Int): Part? {
            if (type == EventNames.EV_KEY && value == 0) return null
            return values().firstOrNull { it.type == type && it.code == code }
        }
    }
}

/** 마지막으로 눌린 부위 (그림에서 색칠/화살표로 표시) + 그림에 없는 입력 */
object RingEvents {
    @Volatile var lastPart: Part? = null
    @Volatile var lastTime = 0L
    @Volatile var lastOther: Rule? = null
    @Volatile var lastOtherText = ""
    val listeners = CopyOnWriteArraySet<Runnable>()

    fun lit(p: Part): Boolean = lastPart == p && SystemClock.elapsedRealtime() - lastTime < 450

    fun onEvent(type: Int, code: Int, value: Int) {
        val part = Part.fromEvent(type, code, value)
        if (part != null) {
            lastPart = part
            lastTime = SystemClock.elapsedRealtime()
        } else if (type == EventNames.EV_KEY && value == 1 && code != 330 && code != 325 ||
            type == EventNames.EV_REL && value != 0 && code != 0 && code != 1 && code != 11 && code != 12
        ) {
            val r = Rule(type, code, if (type == EventNames.EV_REL) (if (value > 0) 1 else -1) else 0)
            lastOther = r
            lastOtherText = r.label()
        } else return
        for (l in listeners) l.run()
    }
}

/** 입력 모니터: 수신한 입력 원본을 최근 순으로 보관 (범용 탭) */
object EventMonitor {
    private const val MAX = 120
    private val q = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun add(dev: String, type: Int, code: Int, value: Int) {
        if (q.size >= MAX) q.removeFirst()
        q.addLast("${fmt.format(Date())}  $dev  ${EventNames.typeName(type)} ${EventNames.codeName(type, code)} = $value")
    }

    /** 최신이 위로 */
    @Synchronized
    fun snapshot(n: Int): String {
        val it = q.descendingIterator()
        val sb = StringBuilder()
        var i = 0
        while (it.hasNext() && i < n) {
            sb.append(it.next()).append('\n')
            i++
        }
        return sb.toString().trimEnd()
    }

    @Synchronized
    fun clear() = q.clear()
}

/** JX-11 그림. 지정된 부위는 파랗게, 지금 눌린 부위는 주황색 + 물리 방향 화살표로 표시. 부위를 누르면 onPart 호출 */
class RingView(ctx: Context) : View(ctx) {

    var assigned: Set<Part> = emptySet()
    var onPart: ((Part) -> Unit)? = null

    private val line = 0xFF2B3040.toInt()
    private val accent = 0xFF3D5AFE.toInt()
    private val hot = 0xFFFF9800.toInt()
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = line
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    // 그림 좌표계 (원본 그림 1256px 기준). 표시 영역: x 340..915, y 55..875
    private val x0 = 340f
    private val y0 = 55f
    private val gw = 575f
    private val gh = 820f
    private var sc = 1f
    private var ox = 0f
    private var oy = 0f

    private val wheel = RectF(570f, 160f, 685f, 360f)
    private val oval = RectF(580f, 500f, 678f, 675f)
    private val circleC = floatArrayOf(627f, 748f, 42f)
    private val leftC = floatArrayOf(480f, 466f)
    private val rightC = floatArrayOf(775f, 466f)

    private val ticker = Runnable { invalidate() }
    private val repaint = Runnable { postInvalidate() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        RingEvents.listeners.add(repaint)
    }

    override fun onDetachedFromWindow() {
        RingEvents.listeners.remove(repaint)
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val h = (resources.displayMetrics.density * 330).toInt()
        setMeasuredDimension(w, h)
    }

    private fun setup() {
        sc = minOf(width / gw, height / gh)
        ox = (width - gw * sc) / 2f
        oy = (height - gh * sc) / 2f
    }

    private fun bodyPath(): Path = Path().apply {
        moveTo(628f, 75f)
        cubicTo(790f, 75f, 870f, 200f, 880f, 430f)
        cubicTo(885f, 560f, 880f, 700f, 830f, 830f)
        lineTo(815f, 875f)
        moveTo(628f, 75f)
        cubicTo(466f, 75f, 386f, 200f, 376f, 430f)
        cubicTo(371f, 560f, 376f, 700f, 426f, 830f)
        lineTo(441f, 875f)
    }

    private fun padPath(): Path = Path().apply {
        moveTo(420f, 390f)
        quadTo(628f, 445f, 835f, 390f)
        lineTo(835f, 690f)
        cubicTo(835f, 780f, 760f, 825f, 628f, 825f)
        cubicTo(496f, 825f, 420f, 780f, 420f, 690f)
        close()
    }

    private fun tri(left: Boolean, c: FloatArray): Path = Path().apply {
        val cx = c[0]
        val cy = c[1]
        if (left) {
            moveTo(cx - 28f, cy)
            lineTo(cx + 26f, cy - 27f)
            lineTo(cx + 26f, cy + 27f)
        } else {
            moveTo(cx + 28f, cy)
            lineTo(cx - 26f, cy - 27f)
            lineTo(cx - 26f, cy + 27f)
        }
        close()
    }

    private fun paintPart(c: Canvas, p: Part, draw: (Paint) -> Unit) {
        val lit = RingEvents.lit(p)
        fillP.color = if (lit) hot else if (p in assigned) 0xFFD6E0FF.toInt() else 0xFFFFFFFF.toInt()
        draw(fillP)
        strokeP.color = if (lit) 0xFFE65100.toInt() else if (p in assigned) accent else line
        strokeP.strokeWidth = if (lit || p in assigned) 8f else 5f
        draw(strokeP)
        strokeP.color = line
        strokeP.strokeWidth = 5f
    }

    private fun arrow(c: Canvas, cx: Float, cy: Float, up: Boolean) {
        val d = if (up) -1f else 1f
        fillP.color = 0xFFE65100.toInt()
        val p = Path().apply {
            moveTo(cx, cy + d * 70f)
            lineTo(cx - 55f, cy - d * 10f)
            lineTo(cx - 20f, cy - d * 10f)
            lineTo(cx - 20f, cy - d * 60f)
            lineTo(cx + 20f, cy - d * 60f)
            lineTo(cx + 20f, cy - d * 10f)
            lineTo(cx + 55f, cy - d * 10f)
            close()
        }
        c.drawPath(p, fillP)   // 위로 굴리면 위를 가리키는 화살표
    }

    override fun onDraw(c: Canvas) {
        setup()
        c.save()
        c.translate(ox, oy)
        c.scale(sc, sc)
        c.translate(-x0, -y0)

        // 몸통
        val body = Path().apply {
            moveTo(628f, 75f)
            cubicTo(790f, 75f, 870f, 200f, 880f, 430f)
            cubicTo(885f, 560f, 880f, 700f, 830f, 830f)
            lineTo(815f, 875f)
            lineTo(441f, 875f)
            lineTo(426f, 830f)
            cubicTo(376f, 700f, 371f, 560f, 376f, 430f)
            cubicTo(386f, 200f, 466f, 75f, 628f, 75f)
            close()
        }
        fillP.color = 0xFFF4F6FA.toInt()
        c.drawPath(body, fillP)
        c.drawPath(bodyPath(), strokeP)
        // 옆 버튼
        for (sx in floatArrayOf(358f, 876f)) {
            val r = RectF(sx, 580f, sx + 24f, 770f)
            fillP.color = 0xFFEDEFF4.toInt()
            c.drawRoundRect(r, 10f, 10f, fillP)
            c.drawRoundRect(r, 10f, 10f, strokeP)
        }
        // 패드
        fillP.color = 0xFFFFFFFF.toInt()
        c.drawPath(padPath(), fillP)
        c.drawPath(padPath(), strokeP)

        // 휠 (위/아래 모두 같은 부품 → 굴린 방향 쪽 절반을 칠함)
        val upLit = RingEvents.lit(Part.WHEEL_UP)
        val downLit = RingEvents.lit(Part.WHEEL_DOWN)
        val wheelAssigned = Part.WHEEL_UP in assigned || Part.WHEEL_DOWN in assigned
        fillP.color = if (wheelAssigned) 0xFFD6E0FF.toInt() else 0xFFFFFFFF.toInt()
        c.drawRoundRect(wheel, 57f, 57f, fillP)
        c.save()
        c.clipRect(wheel.left, wheel.top, wheel.right, (wheel.top + wheel.bottom) / 2f)
        if (upLit) { fillP.color = hot; c.drawRoundRect(wheel, 57f, 57f, fillP) }
        c.restore()
        c.save()
        c.clipRect(wheel.left, (wheel.top + wheel.bottom) / 2f, wheel.right, wheel.bottom)
        if (downLit) { fillP.color = hot; c.drawRoundRect(wheel, 57f, 57f, fillP) }
        c.restore()
        strokeP.strokeWidth = 3f
        var y = wheel.top + 36f
        while (y < wheel.bottom - 30f) {
            c.drawLine(wheel.left + 14f, y, wheel.right - 14f, y, strokeP)
            y += 17f
        }
        strokeP.strokeWidth = 5f
        strokeP.color = if (upLit || downLit) 0xFFE65100.toInt() else if (wheelAssigned) accent else line
        strokeP.strokeWidth = if (upLit || downLit || wheelAssigned) 8f else 5f
        c.drawRoundRect(wheel, 57f, 57f, strokeP)
        strokeP.color = line
        strokeP.strokeWidth = 5f

        // ◁ ▷ 키
        paintPart(c, Part.KEY_LEFT) { c.drawPath(tri(true, leftC), it) }
        paintPart(c, Part.KEY_RIGHT) { c.drawPath(tri(false, rightC), it) }
        // 타원 / 원형
        paintPart(c, Part.OVAL) { c.drawRoundRect(oval, 49f, 49f, it) }
        paintPart(c, Part.CIRCLE) { c.drawCircle(circleC[0], circleC[1], circleC[2], it) }

        // 눌린 방향 화살표 (물리적으로 굴린/누른 방향)
        if (upLit) arrow(c, (wheel.left + wheel.right) / 2f + 150f, (wheel.top + wheel.bottom) / 2f - 40f, true)
        if (downLit) arrow(c, (wheel.left + wheel.right) / 2f + 150f, (wheel.top + wheel.bottom) / 2f + 40f, false)

        c.restore()

        val any = Part.values().any { RingEvents.lit(it) }
        if (any) postDelayed(ticker, 50)
    }

    private fun toImg(px: Float, py: Float) = floatArrayOf((px - ox) / sc + x0, (py - oy) / sc + y0)

    private fun hit(px: Float, py: Float): Part? {
        val p = toImg(px, py)
        val x = p[0]
        val yy = p[1]
        if (x in 540f..715f && yy in 140f..380f) return if (yy < 260f) Part.WHEEL_UP else Part.WHEEL_DOWN
        fun near(c: FloatArray, r: Float) = (x - c[0]) * (x - c[0]) + (yy - c[1]) * (yy - c[1]) < r * r
        if (near(leftC, 80f)) return Part.KEY_LEFT
        if (near(rightC, 80f)) return Part.KEY_RIGHT
        if (x in 560f..700f && yy in 480f..695f) return Part.OVAL
        if (near(circleC, 70f)) return Part.CIRCLE
        return null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return hit(e.x, e.y) != null
            MotionEvent.ACTION_UP -> {
                val p = hit(e.x, e.y)
                if (p != null) {
                    performClick()
                    onPart?.invoke(p)
                }
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
