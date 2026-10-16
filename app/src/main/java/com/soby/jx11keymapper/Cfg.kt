package com.soby.jx11keymapper

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 프리셋: 키 매핑 + 감도 묶음. apps 에 연결된 앱이 화면 앞에 있으면 이 프리셋이 적용된다.
 * fallback=true 인 프리셋은 어떤 프리셋에도 연결되지 않은 앱에서 쓰인다 (사용자가 직접 켠 경우만).
 */
data class Preset(
    val id: String,
    val name: String,
    val map: Map<Act, List<Rule>> = emptyMap(),
    val apps: List<String> = emptyList(),
    val fallback: Boolean = false,
    val unit: Int = 100,        // 스와이프가 이만큼(‰) 움직일 때마다 동작 1회
    val cooldown: Int = 120,    // 동작 간 최소 간격(ms)
    val steps: Int = 1          // 동작 1회당 반복 횟수
) {
    fun rules(a: Act): List<Rule> = map[a] ?: emptyList()
}

/** 앱 설정 (메인 프로세스 전용 싱글톤). 값은 즉시 반영되고 save()로 저장. */
object Cfg {
    private var sp: SharedPreferences? = null

    @Volatile var backend: Int = 0              // 0=자동(Shizuku 우선→무선 ADB), 1=Shizuku만, 2=무선 ADB만
    @Volatile var adbPort: Int = 0              // 무선 ADB 서버(AdbServer) 로컬 포트
    @Volatile var adbToken: String = ""         // 서버 접속 토큰
    @Volatile var deviceFilter: String = "JX"
    @Volatile var mapVersion: Int = 0           // 프리셋/표가 바뀌면 증가 → 화면 다시 그림
    @Volatile var threshold: Float = 1.0f       // 동작 1회에 필요한 누적량 (1.0 고정)
    @Volatile var swipeMinPermille: Int = 15    // 입력 등록(학습) 때 스와이프로 인정하는 최소 이동량 (‰)
    @Volatile var idleResetMs: Int = 800        // 이 시간 멈추면 누적 초기화
    @Volatile var mediaFixed: Boolean = false   // true: 미디어 볼륨만 직접 조절
    @Volatile var volumeKey: Boolean = true     // true: 볼륨을 키 입력으로 주입(이북 페이지 넘김 등), false: AudioManager 직접 조절
    @Volatile var grab: Boolean = true          // JX-11 독점: 원래 터치/스크롤 동작 막기 (EVIOCGRAB)
    @Volatile var imeWithKeyboard: Boolean = true  // JX-11 연결 중에도 화면 키보드 표시
    @Volatile var showUi: Boolean = true
    @Volatile var wakeLock: Boolean = true
    @Volatile var enabled: Boolean = false      // 사용자가 켜 둔 상태
    @Volatile var einkBlackMs: Int = 300        // E-ink 새로고침: 검정 유지 시간
    @Volatile var einkWhiteMs: Int = 300        // E-ink 새로고침: 흰색 유지 시간
    @Volatile var einkRepeat: Int = 1           // E-ink 새로고침: 검정↔흰색 반복 횟수

    // ── 프리셋 ──
    @Volatile var presets: List<Preset> = emptyList()
    @Volatile var editId: String = ""           // 화면에서 편집 중인 프리셋
    @Volatile var runtimeId: String = ""        // 지금 실제로 적용 중인 프리셋 (앞에 있는 앱 기준)
    @Volatile var runtimePkg: String = ""       // 마지막으로 확인한 앞 앱

    // ── 지금 적용 중인 값 (적용 프리셋에서 복사됨. 입력 처리가 이 값을 쓴다) ──
    @Volatile var map: Map<Act, List<Rule>> = emptyMap()
    @Volatile var swipeUnitPermille: Int = 100
    @Volatile var cooldownMs: Int = 120
    @Volatile var stepsPerFire: Int = 1

    // ───────── 프리셋 선택/적용 ─────────

    val editing: Preset?
        get() = presets.firstOrNull { it.id == editId } ?: presets.firstOrNull()

    /** 편집 중인 프리셋에서 이 기능에 지정된 입력 */
    fun rulesOf(a: Act): List<Rule> = editing?.rules(a) ?: emptyList()

    fun resolve(pkg: String?): Preset? {
        val ps = presets
        if (pkg != null) {
            val m = ps.firstOrNull { pkg in it.apps }
            if (m != null) return m
        }
        return ps.firstOrNull { it.fallback }
    }

    @Synchronized
    fun setRuntimePackage(pkg: String?) {
        runtimePkg = pkg ?: ""
        applyRuntime(resolve(pkg))
    }

    @Synchronized
    fun refreshRuntime() {
        setRuntimePackage(if (runtimePkg.isEmpty()) null else runtimePkg)
    }

    private fun applyRuntime(p: Preset?) {
        val changed = (p?.id ?: "") != runtimeId
        runtimeId = p?.id ?: ""
        map = p?.map ?: emptyMap()
        swipeUnitPermille = p?.unit ?: 100
        cooldownMs = p?.cooldown ?: 120
        stepsPerFire = p?.steps ?: 1
        if (changed) Diag.i("PRESET", "적용 프리셋: ${p?.name ?: "없음"} (앞 앱 ${if (runtimePkg.isEmpty()) "?" else runtimePkg})")
    }

    // ───────── 프리셋 편집 ─────────

    @Synchronized
    private fun commit(list: List<Preset>, bump: Boolean = true) {
        presets = list
        if (presets.none { it.id == editId }) editId = presets.firstOrNull()?.id ?: ""
        if (bump) mapVersion++
        save()
        refreshRuntime()
    }

    /** 편집 중인 프리셋을 바꾼다 */
    fun updateEditing(bump: Boolean = true, f: (Preset) -> Preset) {
        val e = editing ?: return
        commit(presets.map { if (it.id == e.id) f(it) else it }, bump)
    }

    /** 같은 입력은 한 기능에만 지정: 편집 중인 프리셋 안에서 다른 기능에 있던 같은 입력은 옮겨진다 */
    fun addRule(a: Act, r: Rule) {
        updateEditing { p ->
            val m = LinkedHashMap<Act, List<Rule>>()
            for (x in Act.values()) m[x] = p.rules(x).filter { it != r }
            m[a] = (m[a] ?: emptyList()) + r
            p.copy(map = m)
        }
    }

    fun removeRule(a: Act, r: Rule) {
        updateEditing { p -> p.copy(map = p.map + (a to p.rules(a).filter { it != r })) }
    }

    fun removeRuleEverywhere(r: Rule) {
        updateEditing { p ->
            val m = LinkedHashMap<Act, List<Rule>>()
            for (x in Act.values()) m[x] = p.rules(x).filter { it != r }
            p.copy(map = m)
        }
    }

    @Synchronized
    fun newPreset(name: String): String {
        val id = "p" + System.currentTimeMillis()
        editId = id
        commit(presets + Preset(id = id, name = name, fallback = presets.isEmpty()))
        return id
    }

    @Synchronized
    fun deletePreset(id: String) {
        commit(presets.filter { it.id != id })
    }

    @Synchronized
    fun renamePreset(id: String, name: String) {
        commit(presets.map { if (it.id == id) it.copy(name = name) else it })
    }

    @Synchronized
    fun selectEdit(id: String) {
        editId = id
        mapVersion++
        save()
    }

    /** 앱 연결: 같은 앱은 한 프리셋에만 연결되므로 다른 프리셋에 있던 것은 옮겨진다 */
    @Synchronized
    fun setApps(id: String, apps: List<String>) {
        val set = apps.toSet()
        commit(presets.map {
            if (it.id == id) it.copy(apps = apps) else it.copy(apps = it.apps.filter { a -> a !in set })
        })
    }

    /** 연결 안 된 모든 앱에 적용: 하나의 프리셋만 가질 수 있다 */
    @Synchronized
    fun setFallback(id: String, on: Boolean) {
        commit(presets.map {
            if (it.id == id) it.copy(fallback = on) else if (on) it.copy(fallback = false) else it
        })
    }

    // ───────── 저장/불러오기 ─────────

    fun encodeMap(m: Map<Act, List<Rule>>): String =
        Act.values().joinToString(";") { "${it.name}=${Rule.encodeList(m[it] ?: emptyList())}" }

    fun decodeMap(s: String): Map<Act, List<Rule>> {
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

    private fun toJson(p: Preset): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("map", encodeMap(p.map))
        put("apps", JSONArray(p.apps))
        put("fallback", p.fallback)
        put("unit", p.unit)
        put("cd", p.cooldown)
        put("steps", p.steps)
    }

    private fun fromJson(o: JSONObject): Preset {
        val apps = ArrayList<String>()
        val arr = o.optJSONArray("apps")
        if (arr != null) for (i in 0 until arr.length()) apps.add(arr.getString(i))
        return Preset(
            id = o.getString("id"),
            name = o.optString("name", "프리셋"),
            map = decodeMap(o.optString("map", "")),
            apps = apps,
            fallback = o.optBoolean("fallback", false),
            unit = o.optInt("unit", 100),
            cooldown = o.optInt("cd", 120),
            steps = o.optInt("steps", 1)
        )
    }

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        sp = p
        deviceFilter = p.getString("deviceFilter", "JX") ?: "JX"
        threshold = 1.0f
        swipeMinPermille = p.getInt("swipeMinPermille", 15)
        idleResetMs = if (p.getBoolean("mig_v06", false)) p.getInt("idleResetMs", 800) else 800
        mediaFixed = p.getBoolean("mediaFixed", false)
        volumeKey = p.getBoolean("volumeKey", true)
        backend = p.getInt("backend", 0)
        adbPort = p.getInt("adbPort", 0)
        adbToken = p.getString("adbToken", "") ?: ""
        grab = p.getBoolean("grab", true)
        imeWithKeyboard = p.getBoolean("imeWithKeyboard", true)
        showUi = p.getBoolean("showUi", true)
        wakeLock = p.getBoolean("wakeLock", true)
        enabled = p.getBoolean("enabled", false)
        einkBlackMs = p.getInt("einkBlackMs", 300)
        einkWhiteMs = p.getInt("einkWhiteMs", 300)
        einkRepeat = p.getInt("einkRepeat", 1)

        val saved = p.getString("presets", null)
        val list = ArrayList<Preset>()
        if (saved != null) {
            try {
                val arr = JSONArray(saved)
                for (i in 0 until arr.length()) list.add(fromJson(arr.getJSONObject(i)))
            } catch (t: Throwable) {
                Diag.e("CONF", "프리셋 불러오기 실패", t)
            }
        } else {
            // 이전 버전(프리셋 없음) 설정 → 프리셋 1 로 옮김. 동작이 그대로 이어지도록 '연결 안 된 앱'에도 적용
            // (이전의 기본 매핑은 버리고 비어 있는 상태로 시작: 기본 프리셋 없음)
            list.add(
                Preset(
                    id = "p1",
                    name = "프리셋 1",
                    map = emptyMap(),
                    fallback = true,
                    unit = p.getInt("swipeUnitPermille", 100),
                    cooldown = p.getInt("cooldownMs", 120),
                    steps = p.getInt("stepsPerFire", 1)
                )
            )
            Diag.i("CONF", "프리셋 첫 생성: 비어 있는 '프리셋 1' (감도는 기존 값 유지)")
        }
        presets = list
        editId = p.getString("editId", "") ?: ""
        if (presets.none { it.id == editId }) editId = presets.firstOrNull()?.id ?: ""
        p.edit().putBoolean("mig_v06", true).apply()
        save()
        setRuntimePackage(null)
    }

    fun save() {
        val arr = JSONArray()
        for (x in presets) arr.put(toJson(x))
        sp?.edit()
            ?.putString("deviceFilter", deviceFilter)
            ?.putString("presets", arr.toString())
            ?.putString("editId", editId)
            ?.putInt("swipeMinPermille", swipeMinPermille)
            ?.putInt("idleResetMs", idleResetMs)
            ?.putBoolean("mediaFixed", mediaFixed)
            ?.putBoolean("volumeKey", volumeKey)
            ?.putInt("backend", backend)
            ?.putInt("adbPort", adbPort)
            ?.putString("adbToken", adbToken)
            ?.putBoolean("grab", grab)
            ?.putBoolean("imeWithKeyboard", imeWithKeyboard)
            ?.putBoolean("showUi", showUi)
            ?.putBoolean("wakeLock", wakeLock)
            ?.putBoolean("enabled", enabled)
            ?.putInt("einkBlackMs", einkBlackMs)
            ?.putInt("einkWhiteMs", einkWhiteMs)
            ?.putInt("einkRepeat", einkRepeat)
            ?.apply()
    }

    fun describe(): String =
        "필터='$deviceFilter' 프리셋=${presets.joinToString { "${it.name}(앱${it.apps.size}${if (it.fallback) ",나머지" else ""},unit=${it.unit},cd=${it.cooldown},steps=${it.steps})" }} " +
            "편집=$editId 적용=$runtimeId idle=${idleResetMs}ms media=$mediaFixed backend=$backend adbPort=$adbPort " +
            "vkey=$volumeKey grab=$grab ui=$showUi wake=$wakeLock enabled=$enabled eink=${einkBlackMs}/${einkWhiteMs}ms x$einkRepeat"
}
