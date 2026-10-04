package com.soby.jx11keymapper

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent

/** 동작 종류. VOLUME/MUTE/MEDIA 는 앱 권한만으로, KEY/NOTIF 는 Shizuku(셸 권한)가 필요하다. */
enum class Kind { VOLUME, MUTE, MEDIA, KEY, NOTIF }

/** 매핑 가능한 기능 목록 (표의 행). repeatOk: 키를 길게 눌러 반복되는 입력(REPEAT)도 허용 */
enum class Act(val label: String, val kind: Kind, val code: Int = 0, val repeatOk: Boolean = false) {
    VOL_UP("볼륨 올림", Kind.VOLUME, 0, true),
    VOL_DOWN("볼륨 내림", Kind.VOLUME, 0, true),
    MUTE("음소거", Kind.MUTE),
    PLAY_PAUSE("재생/일시정지", Kind.MEDIA, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE),
    NEXT("다음 곡", Kind.MEDIA, KeyEvent.KEYCODE_MEDIA_NEXT),
    PREV("이전 곡", Kind.MEDIA, KeyEvent.KEYCODE_MEDIA_PREVIOUS),
    BACK("뒤로", Kind.KEY, KeyEvent.KEYCODE_BACK),
    HOME("홈", Kind.KEY, KeyEvent.KEYCODE_HOME),
    RECENTS("최근 앱", Kind.KEY, KeyEvent.KEYCODE_APP_SWITCH),
    UP("위 (방향키)", Kind.KEY, KeyEvent.KEYCODE_DPAD_UP, true),
    DOWN("아래 (방향키)", Kind.KEY, KeyEvent.KEYCODE_DPAD_DOWN, true),
    LEFT("왼쪽 (방향키)", Kind.KEY, KeyEvent.KEYCODE_DPAD_LEFT, true),
    RIGHT("오른쪽 (방향키)", Kind.KEY, KeyEvent.KEYCODE_DPAD_RIGHT, true),
    OK("확인", Kind.KEY, KeyEvent.KEYCODE_DPAD_CENTER),
    NOTIF("알림창 열기", Kind.NOTIF),
    POWER("전원 (화면 켜기/끄기)", Kind.KEY, KeyEvent.KEYCODE_POWER);

    val needsShell: Boolean
        get() = kind == Kind.KEY || kind == Kind.NOTIF
}

/** 입력 규칙. type: 1=EV_KEY, 2=EV_REL, 100=제스처(1↑ 2↓ 3← 4→ 5탭) / sign: +1, -1, 0(키: 눌림) */
data class Rule(val type: Int, val code: Int, val sign: Int) {
    fun encode(): String = "$type:$code:" + (if (sign > 0) "+" else if (sign < 0) "-" else "*")

    fun label(): String = EventNames.label(type, code, sign)

    /** 이 규칙에 해당하면 가중치(>0), 아니면 0 */
    fun weight(t: Int, c: Int, v: Int): Float {
        if (t != type || c != code) return 0f
        if (type == EventNames.EV_REL) {
            if (v == 0) return 0f
            val s = if (v > 0) 1 else -1
            return if (sign == 0 || sign == s) Math.abs(v).toFloat() else 0f
        }
        if (type == EventNames.EV_KEY) {
            return if (v == 1 || v == 2) 1f else 0f   // 눌림 / 길게 눌러 반복
        }
        if (type == EventNames.EV_GESTURE) return 1f
        return 0f
    }

    companion object {
        fun decode(s: String): Rule? {
            val p = s.split(":")
            if (p.size != 3) return null
            val t = p[0].toIntOrNull() ?: return null
            val c = p[1].toIntOrNull() ?: return null
            val sg = when (p[2]) {
                "+" -> 1
                "-" -> -1
                else -> 0
            }
            return Rule(t, c, sg)
        }

        fun decodeList(s: String): List<Rule> =
            if (s.isBlank()) emptyList() else s.split(",").mapNotNull { decode(it.trim()) }

        fun encodeList(l: List<Rule>): String = l.joinToString(",") { it.encode() }
    }
}

/** 매핑 결과: 어떤 기능을, 얼마의 가중치로, 키 입력인지 */
class Hit(val act: Act, val weight: Float, val key: Boolean)

object Mapper {
    fun classify(type: Int, code: Int, value: Int): Hit? {
        for ((act, rules) in Cfg.map) {
            for (r in rules) {
                val w = r.weight(type, code, value)
                if (w > 0f) {
                    // 길게 눌러 반복되는 키는 반복 허용 기능(볼륨/방향키)에서만 인정
                    if (type == EventNames.EV_KEY && value == 2 && !act.repeatOk) return null
                    return Hit(act, w, type == EventNames.EV_KEY || (type == EventNames.EV_GESTURE && code == 5))
                }
            }
        }
        return null
    }
}

/** 스크롤량을 누적해 민감도/쿨다운에 맞춰 동작 횟수로 변환. 키 입력은 즉시 1회 동작 */
class Engine(private val action: (Act, Int) -> Unit) {
    private var acc = 0f
    private var lastAct: Act? = null
    private var lastT = 0L
    private var lastFire = 0L

    @Volatile
    var fireCount = 0L

    @Synchronized
    fun feed(hit: Hit) {
        val now = SystemClock.elapsedRealtime()
        val thr = maxOf(Cfg.threshold, 0.1f)
        val weight = if (hit.key) thr else hit.weight
        if (now - lastT > Cfg.idleResetMs) acc = 0f
        if (lastAct != hit.act) {
            acc = 0f
            lastAct = hit.act
        }
        lastT = now
        acc += weight
        if (acc < thr) {
            Diag.v("MAP", "${hit.act.label} 누적 ${"%.2f".format(acc)}/${"%.2f".format(thr)}")
            return
        }
        if (now - lastFire < Cfg.cooldownMs) {
            acc = if (hit.key) 0f else minOf(acc, thr * 3)
            Diag.v("MAP", "${hit.act.label} 쿨다운 중 (보류)")
            return
        }
        val units = if (hit.key) 1 else minOf((acc / thr).toInt(), 4)
        acc = if (hit.key) 0f else acc % thr
        val n = units * (if (hit.act.repeatOk) Cfg.stepsPerFire else 1)
        lastFire = now
        fireCount++
        Diag.i("FIRE", "${hit.act.label} x$n (thr=${"%.2f".format(thr)})")
        action(hit.act, n)
    }

    @Synchronized
    fun reset() {
        acc = 0f
        lastAct = null
    }
}

/** 볼륨 조절 (앱 권한만으로 가능: AudioManager) */
object VolumeAction {
    fun apply(ctx: Context, up: Boolean, n: Int) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val dir = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            val flags = if (Cfg.showUi) AudioManager.FLAG_SHOW_UI else 0
            repeat(n) {
                if (Cfg.mediaFixed) {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, flags)
                } else {
                    am.adjustVolume(dir, flags)
                }
            }
            Diag.v("ACTION", "adjust ${if (up) "RAISE" else "LOWER"} x$n media=${Cfg.mediaFixed}")
        } catch (t: Throwable) {
            Diag.e("ACTION", "볼륨 조절 실패 (방해금지 모드 등 확인)", t)
        }
    }
}

/** 기능별 실제 동작 실행 */
object ActionRunner {
    fun run(ctx: Context, act: Act, n: Int, shell: IInputService?) {
        val t0 = SystemClock.elapsedRealtime()
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            when (act.kind) {
                Kind.VOLUME -> VolumeAction.apply(ctx, act == Act.VOL_UP, n)
                Kind.MUTE -> {
                    val flags = if (Cfg.showUi) AudioManager.FLAG_SHOW_UI else 0
                    am.adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE, flags)
                }
                Kind.MEDIA -> repeat(n) {
                    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, act.code))
                    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, act.code))
                }
                Kind.KEY -> {
                    if (shell == null) {
                        Diag.w("ACTION", "'${act.label}'은(는) Shizuku 연결이 필요합니다 (현재 미연결)")
                    } else {
                        repeat(n) { shell.sendKey(act.code) }
                    }
                }
                Kind.NOTIF -> {
                    if (shell == null) {
                        Diag.w("ACTION", "'${act.label}'은(는) Shizuku 연결이 필요합니다 (현재 미연결)")
                    } else {
                        shell.expandNotifications()
                    }
                }
            }
            Diag.d("ACTION", "${act.label} x$n 완료 ${SystemClock.elapsedRealtime() - t0}ms")
        } catch (t: Throwable) {
            Diag.e("ACTION", "'${act.label}' 실행 실패", t)
        }
    }
}

/**
 * 표의 ＋/－ 동작: 다음에 들어오는 의미 있는 입력 1개를 기능에 등록(ADD)하거나,
 * 삭제 대상으로 확인(DELETE)한다. 학습 중에는 입력을 소비해 다른 동작이 같이 실행되지 않게 한다.
 */
object Learner {
    enum class Outcome { ADDED, FOUND, MISSING }

    @Volatile
    var act: Act? = null

    @Volatile
    var deleteMode = false

    @Volatile
    private var deadline = 0L

    @Volatile
    private var onResult: ((Outcome, Rule) -> Unit)? = null

    val active: Boolean
        get() = act != null && SystemClock.elapsedRealtime() < deadline

    fun begin(a: Act, delete: Boolean, cb: (Outcome, Rule) -> Unit) {
        act = a
        deleteMode = delete
        deadline = SystemClock.elapsedRealtime() + 10_000L
        onResult = cb
        Diag.i("LEARN", "${if (delete) "삭제" else "추가"} 대기: ${a.label} (10초)")
    }

    fun cancel() {
        if (act != null) Diag.i("LEARN", "취소")
        act = null
    }

    /** @return 학습 중이라 이벤트를 소비했으면 true */
    fun offer(type: Int, code: Int, value: Int): Boolean {
        val a = act
        if (a == null || SystemClock.elapsedRealtime() >= deadline) {
            act = null
            return false
        }
        val qualifies = (type == EventNames.EV_REL && value != 0 &&
            code != 0 && code != 1 && code != 11 && code != 12) ||
            (type == EventNames.EV_KEY && value == 1 && code != 330 && code != 325) ||   // BTN_TOUCH 등은 제외
            (type == EventNames.EV_GESTURE)
        if (!qualifies) return true

        val sign = if (type == EventNames.EV_REL) (if (value > 0) 1 else -1) else 0
        val rule = Rule(type, code, sign)
        val cb = onResult
        act = null
        if (deleteMode) {
            if (Cfg.rulesOf(a).contains(rule)) {
                Diag.i("LEARN", "삭제 대상 확인: ${a.label} ← ${rule.label()}")
                cb?.invoke(Outcome.FOUND, rule)
            } else {
                Diag.i("LEARN", "삭제 대상 아님: ${a.label} ← ${rule.label()}")
                cb?.invoke(Outcome.MISSING, rule)
            }
        } else {
            Cfg.addRule(a, rule)
            Diag.i("LEARN", "추가됨: ${a.label} ← ${rule.label()}")
            cb?.invoke(Outcome.ADDED, rule)
        }
        return true
    }
}
