package com.soby.jx11keymapper

import android.content.Context
import android.content.SharedPreferences

/** 앱 설정 (메인 프로세스 전용 싱글톤). 값은 즉시 반영되고 save()로 저장. */
object Cfg {
    private var sp: SharedPreferences? = null

    @Volatile var deviceFilter: String = "JX"
    @Volatile var map: Map<Act, List<Rule>> = defaultMap()   // 기능별 입력 규칙 (표의 내용)
    @Volatile var mapVersion: Int = 0                        // 표가 바뀌면 증가 → 화면 다시 그림
    @Volatile var threshold: Float = 1.0f       // 동작 1회에 필요한 스크롤량(클수록 둔감)
    @Volatile var cooldownMs: Int = 120         // 동작 간 최소 간격
    @Volatile var idleResetMs: Int = 400        // 이 시간 멈추면 누적 초기화
    @Volatile var stepsPerFire: Int = 1         // 볼륨/방향키 1회 동작당 반복 수
    @Volatile var mediaFixed: Boolean = false   // true: 미디어 볼륨 고정, false: 볼륨키와 동일(활성 스트림)
    @Volatile var volumeKey: Boolean = true     // true: 볼륨을 키 입력으로 주입(이북 페이지 넘김 등), false: AudioManager 직접 조절
    @Volatile var showUi: Boolean = true
    @Volatile var wakeLock: Boolean = true
    @Volatile var enabled: Boolean = false      // 사용자가 켜 둔 상태

    fun defaultMap(): Map<Act, List<Rule>> {
        val m = LinkedHashMap<Act, List<Rule>>()
        for (a in Act.values()) m[a] = emptyList()
        m[Act.VOL_UP] = Rule.decodeList("2:8:+,1:104:*,100:1:*")     // REL_WHEEL(+), KEY_PAGEUP, 스와이프 ↑
        m[Act.VOL_DOWN] = Rule.decodeList("2:8:-,1:109:*,100:2:*")   // REL_WHEEL(-), KEY_PAGEDOWN, 스와이프 ↓
        return m
    }

    fun rulesOf(a: Act): List<Rule> = map[a] ?: emptyList()

    private fun encodeMap(m: Map<Act, List<Rule>>): String =
        Act.values().joinToString(";") { "${it.name}=${Rule.encodeList(m[it] ?: emptyList())}" }

    private fun decodeMap(s: String): Map<Act, List<Rule>> {
        val m = LinkedHashMap<Act, List<Rule>>()
        for (a in Act.values()) m[a] = emptyList()
        for (part in s.split(";")) {
            val kv = part.split("=")
            if (kv.size != 2) continue
            val a = try {
                Act.valueOf(kv[0])
            } catch (e: IllegalArgumentException) {
                Diag.w("CONF", "알 수 없는 기능 무시: ${kv[0]}")
                continue
            }
            m[a] = Rule.decodeList(kv[1])
        }
        return m
    }

    /** 같은 입력은 한 기능에만 지정: 다른 기능에 있던 같은 입력은 옮겨진다 */
    @Synchronized
    fun addRule(a: Act, r: Rule) {
        val m = LinkedHashMap<Act, List<Rule>>(map)
        for (x in Act.values()) m[x] = (m[x] ?: emptyList()).filter { it != r }
        m[a] = (m[a] ?: emptyList()) + r
        map = m
        mapVersion++
        save()
    }

    @Synchronized
    fun removeRule(a: Act, r: Rule) {
        val m = LinkedHashMap<Act, List<Rule>>(map)
        m[a] = (m[a] ?: emptyList()).filter { it != r }
        map = m
        mapVersion++
        save()
    }

    @Synchronized
    fun resetRules() {
        map = defaultMap()
        mapVersion++
        save()
    }

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        sp = p
        deviceFilter = p.getString("deviceFilter", "JX") ?: "JX"
        val saved = p.getString("map", null)
        map = if (saved == null) defaultMap() else decodeMap(saved)
        if (!p.getBoolean("mig_gesture", false)) {
            // v0.2.2 이전에 잘못 학습된 BTN_TOUCH(터치) 규칙 제거 + 스와이프 기본 규칙 추가
            val m = LinkedHashMap<Act, List<Rule>>(map)
            var removed = 0
            for (a in Act.values()) {
                val keep = (m[a] ?: emptyList()).filter { !(it.type == EventNames.EV_KEY && (it.code == 330 || it.code == 325)) }
                removed += (m[a] ?: emptyList()).size - keep.size
                m[a] = keep
            }
            if (m.values.none { l -> l.any { it.type == EventNames.EV_GESTURE } }) {
                m[Act.VOL_UP] = (m[Act.VOL_UP] ?: emptyList()) + Rule(EventNames.EV_GESTURE, 1, 0)
                m[Act.VOL_DOWN] = (m[Act.VOL_DOWN] ?: emptyList()) + Rule(EventNames.EV_GESTURE, 2, 0)
            }
            map = m
            p.edit().putBoolean("mig_gesture", true).putString("map", encodeMap(m)).apply()
            Diag.i("CONF", "v0.2.2 마이그레이션: BTN_TOUCH 규칙 ${removed}개 제거, 스와이프 기본 규칙 추가")
        }
        threshold = p.getFloat("threshold", 1.0f)
        cooldownMs = p.getInt("cooldownMs", 120)
        idleResetMs = p.getInt("idleResetMs", 400)
        stepsPerFire = p.getInt("stepsPerFire", 1)
        mediaFixed = p.getBoolean("mediaFixed", false)
        volumeKey = p.getBoolean("volumeKey", true)
        showUi = p.getBoolean("showUi", true)
        wakeLock = p.getBoolean("wakeLock", true)
        enabled = p.getBoolean("enabled", false)
    }

    fun save() {
        sp?.edit()
            ?.putString("deviceFilter", deviceFilter)
            ?.putString("map", encodeMap(map))
            ?.putFloat("threshold", threshold)
            ?.putInt("cooldownMs", cooldownMs)
            ?.putInt("idleResetMs", idleResetMs)
            ?.putInt("stepsPerFire", stepsPerFire)
            ?.putBoolean("mediaFixed", mediaFixed)
            ?.putBoolean("volumeKey", volumeKey)
            ?.putBoolean("showUi", showUi)
            ?.putBoolean("wakeLock", wakeLock)
            ?.putBoolean("enabled", enabled)
            ?.apply()
    }

    fun describe(): String =
        "필터='$deviceFilter' map=${encodeMap(map)} thr=$threshold cd=${cooldownMs}ms idle=${idleResetMs}ms " +
            "steps=$stepsPerFire media=$mediaFixed vkey=$volumeKey ui=$showUi wake=$wakeLock enabled=$enabled"
}
