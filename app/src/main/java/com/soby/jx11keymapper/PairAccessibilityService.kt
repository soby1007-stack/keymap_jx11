package com.soby.jx11keymapper

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 페어링 코드 자동 입력.
 * 설정의 '페어링 코드로 기기 페어링' 창은 다른 창에 포커스가 넘어가면 닫혀 버려서(알림 입력·오버레이·입력 창 모두 코드가 사라짐),
 * 사람이 코드를 옮겨 적는 대신 설정 화면의 6자리 코드를 접근성으로 직접 읽어 연결 마법사(AdbPairingService)에 넘긴다.
 * - 대상은 설정 앱(패키지명에 'settings' 포함)뿐이고, 연결 마법사가 코드를 기다리는 동안에만 화면을 읽는다
 * - 아무 것도 조작(탭/입력)하지 않는다
 */
class PairAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var connected = false

        fun isEnabled(ctx: Context): Boolean = try {
            val me = ComponentName(ctx, PairAccessibilityService::class.java)
            val v = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            v.split(':').any {
                val c = ComponentName.unflattenFromString(it)
                c != null && c == me
            }
        } catch (t: Throwable) {
            false
        }

        private val CODE_RE = Regex("^\\s*(\\d{3})\\s?(\\d{3})\\s*$")
        private val IP_PORT_RE = Regex("(\\d{1,3}(?:\\.\\d{1,3}){3}):(\\d{4,5})")
    }

    private var lastScan = 0L
    private var lastLog = 0L
    private var lastPkgLog = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        Diag.i("ACCESS", "접근성 서비스 연결됨 (설정 앱 화면에서 페어링 코드 읽기)")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        connected = false
        Diag.i("ACCESS", "접근성 서비스 해제됨")
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!AdbPairingService.wantsScreenRead()) return
        val now = SystemClock.uptimeMillis()
        val pkg = event?.packageName?.toString().orEmpty()
        if (now - lastPkgLog > 3000) {
            lastPkgLog = now
            Diag.d("ACCESS", "화면 이벤트 패키지=$pkg (패키지명에 'settings' 가 든 창만 읽음)")
        }
        // 제조사 설정 앱의 패키지명이 달라도 되도록 'settings' 포함 여부만 본다. 다른 앱 화면은 읽지 않는다
        if (!pkg.contains("settings", ignoreCase = true)) return
        if (now - lastScan < 150) return
        lastScan = now
        try {
            scan(now)
        } catch (t: Throwable) {
            if (now - lastLog > 3000) {
                lastLog = now
                Diag.e("ACCESS", "화면 읽기 실패", t)
            }
        }
    }

    private fun scan(now: Long) {
        val roots = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        try {
            windows?.forEach { w -> w.root?.let { r -> roots.add(r) } }
        } catch (t: Throwable) {
            // 창 목록을 못 얻어도 활성 창만으로 진행
        }
        val texts = ArrayList<String>()
        for (r in roots) collect(r, texts, 0)

        val code = texts.firstNotNullOfOrNull { CODE_RE.find(it)?.let { m -> m.groupValues[1] + m.groupValues[2] } }
        val ipPort = texts.firstNotNullOfOrNull { IP_PORT_RE.find(it) }
        val pairingWord = texts.any { it.contains("페어링") || it.contains("pairing", true) }

        if (now - lastLog > 3000) {
            lastLog = now
            Diag.d("ACCESS", "설정 화면 스캔: 창 ${roots.size}개 · 텍스트 ${texts.size}개 · 코드=${code != null} · 포트표시=${ipPort != null} · '페어링' 문구=$pairingWord")
        }

        if (code != null && pairingWord) {
            val raw = if (ipPort != null) "${ipPort.groupValues[2]} $code" else code
            AdbPairingService.offerFromScreen(raw, isPort = false)
        } else if (code == null && ipPort != null) {
            AdbPairingService.offerFromScreen(ipPort.groupValues[2], isPort = true)
        }
    }

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (n == null || depth > 30 || out.size > 500) return
        n.text?.toString()?.let { if (it.isNotBlank()) out.add(it) }
        n.contentDescription?.toString()?.let { if (it.isNotBlank()) out.add(it) }
        for (i in 0 until n.childCount) collect(n.getChild(i), out, depth + 1)
    }
}
