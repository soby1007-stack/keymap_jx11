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
    @Volatile var showUi: Boolean = true
    @Volatile var wakeLock: Boolean = true
    @Volatile var enabled: Boolean = false      // 사용자가 켜 둔 상태

    fun defaultMap(): Map<Act, List<Rule>> {
        val m = LinkedHashMap<Act, List<Rule>>()
        for (a in Act.values()) m[a] = emptyList()
        m[Act.VOL_UP] = Rule.decodeList("2:8:+,1:104:*")     // REL_WHEEL(+), KEY_PAGEUP
        m[Act.VOL_DOWN] = Rule.decodeList("2:8:-,1:109:*")   // REL_WHEEL(-), KEY_PAGEDOWN
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
        threshold = p.getFloat("threshold", 1.0f)
        cooldownMs = p.getInt("cooldownMs", 120)
        idleResetMs = p.getInt("idleResetMs", 400)
        stepsPerFire = p.getInt("stepsPerFire", 1)
        mediaFixed = p.getBoolean("mediaFixed", false)
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
            ?.putBoolean("showUi", showUi)
            ?.putBoolean("wakeLock", wakeLock)
            ?.putBoolean("enabled", enabled)
            ?.apply()
    }

    fun describe(): String =
        "필터='$deviceFilter' map=${encodeMap(map)} thr=$threshold cd=${cooldownMs}ms idle=${idleResetMs}ms " +
            "steps=$stepsPerFire media=$mediaFixed ui=$showUi wake=$wakeLock enabled=$enabled"
}
