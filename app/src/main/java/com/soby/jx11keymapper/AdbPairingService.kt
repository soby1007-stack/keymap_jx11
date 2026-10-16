package com.soby.jx11keymapper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.IBinder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * mDNS 로 무선 디버깅의 페어링/연결 포트를 찾는다. (BA 앱 방식)
 * - 같은 Wi-Fi 의 다른 기기 서비스도 잡히므로, 이 폰에서 실제로 열려 있는 포트만 인정 (바인드 시도로 확인)
 * - 자기 자신에게 붙는 것이므로 host 는 항상 127.0.0.1
 */
class AdbDiscovery(ctx: Context, private val onPairing: (Ep?) -> Unit) {
    data class Ep(val host: String, val port: Int, val name: String)

    @Volatile var pairing: Ep? = null
    @Volatile var connect: Ep? = null

    private val nsd = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val queue = LinkedBlockingQueue<NsdServiceInfo>()
    private val listeners = ArrayList<NsdManager.DiscoveryListener>()
    @Volatile private var running = false

    /** 이 포트를 바인드할 수 없으면 = 이 폰의 adbd 가 쓰는 중 */
    private fun isLocalPortOpen(port: Int): Boolean = try {
        java.net.ServerSocket(port).close()
        false
    } catch (e: java.io.IOException) {
        true
    }

    @Suppress("DEPRECATION")
    fun start() {
        running = true
        thread(name = "jx11-nsd-resolve") {
            while (running) {
                var polled: NsdServiceInfo? = null
                try {
                    polled = queue.poll(1, TimeUnit.SECONDS)
                } catch (e: InterruptedException) {
                    break
                }
                val info = polled ?: continue
                val latch = CountDownLatch(1)
                try {
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(si: NsdServiceInfo?, errorCode: Int) {
                            Diag.w("ADB", "mDNS resolve 실패 code=$errorCode (${si?.serviceName})")
                            latch.countDown()
                        }

                        override fun onServiceResolved(si: NsdServiceInfo) {
                            val isPair = si.serviceType.contains("pairing")
                            val port = si.port
                            val local = port > 0 && isLocalPortOpen(port)
                            Diag.i("ADB", "mDNS resolve ${si.serviceName} → ${si.host?.hostAddress}:$port 로컬포트열림=$local")
                            if (local) {
                                val ep = Ep("127.0.0.1", port, si.serviceName)
                                if (isPair) {
                                    pairing = ep
                                    onPairing(ep)
                                } else {
                                    connect = ep
                                }
                            } else {
                                Diag.w("ADB", "다른 기기의 서비스로 판단해 무시")
                            }
                            latch.countDown()
                        }
                    })
                    latch.await(3, TimeUnit.SECONDS)
                } catch (t: Throwable) {
                    Diag.w("ADB", "mDNS resolve 예외: ${Diag.chain(t)}")
                }
            }
        }
        for (type in listOf("_adb-tls-pairing._tcp.", "_adb-tls-connect._tcp.")) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                    Diag.e("ADB", "mDNS 탐색 시작 실패 $serviceType code=$errorCode")
                }
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onDiscoveryStarted(serviceType: String?) {
                    Diag.d("ADB", "mDNS 탐색 시작 $serviceType")
                }
                override fun onDiscoveryStopped(serviceType: String?) {}
                override fun onServiceFound(si: NsdServiceInfo) {
                    queue.offer(si)
                }
                override fun onServiceLost(si: NsdServiceInfo) {
                    if (si.serviceType.contains("pairing")) {
                        if (pairing?.name == si.serviceName) {
                            Diag.i("ADB", "페어링 창이 닫힘")
                            pairing = null
                            onPairing(null)
                        }
                    } else if (connect?.name == si.serviceName) {
                        connect = null
                    }
                }
            }
            listeners.add(l)
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l)
            } catch (t: Throwable) {
                Diag.e("ADB", "discoverServices 실패 $type", t)
            }
        }
    }

    fun stop() {
        running = false
        for (l in listeners) {
            try { nsd.stopServiceDiscovery(l) } catch (t: Throwable) { /* 무시 */ }
        }
        listeners.clear()
    }
}

/**
 * 무선 ADB 연결 마법사 (포그라운드 서비스).
 * 1) 이미 서버가 떠 있으면 끝  2) 이전에 페어링했다면 연결 포트로 바로 서버 기동
 * 3) 아니면 상태 알림의 [코드 입력]으로 페어링 → 서버 기동
 * 앱의 [무선 ADB 연결] 버튼을 누를 때마다 처음부터 다시 시작한다.
 */
class AdbPairingService : Service() {

    companion object {
        const val ACTION_START = "com.soby.jx11keymapper.ADB_START"
        const val ACTION_STOP = "com.soby.jx11keymapper.ADB_STOP"
        const val ACTION_CODE = "com.soby.jx11keymapper.ADB_CODE"
        private const val KEY_CODE = "code"
        private const val CH_STATUS = "adb_status_v2"       // 채널 중요도는 만든 뒤 못 바꿈 → ID에 버전
        private const val CH_ALERT = "adb_pairing_alert_v1"
        private const val NID_STATUS = 21
        private const val NID_ALERT = 22

        @Volatile var state: String = "대기 안 함"
        @Volatile var running = false

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, AdbPairingService::class.java).setAction(ACTION_START))
        }
    }

    private var disc: AdbDiscovery? = null
    private val codes = LinkedBlockingQueue<String>()
    private val genLock = Any()
    @Volatile private var flowGen = 0
    @Volatile private var ok = false
    @Volatile private var alertedPort = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "무선 ADB 연결 상태", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "무선 ADB 페어링 코드 알림", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            setShowBadge(false)
        })
        Diag.i("ADB", "알림 허용=${nm.areNotificationsEnabled()} · 팝업채널 중요도=${nm.getNotificationChannel(CH_ALERT)?.importance}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                synchronized(genLock) { flowGen++ }
                running = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CODE -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()?.trim()
                Diag.i("ADB", "알림에서 입력 수신 · 길이=${code?.length ?: -1}")
                if (!code.isNullOrEmpty()) {
                    cancelAlert()
                    codes.offer(code)
                    setState("입력 확인 중…")
                }
                return START_NOT_STICKY
            }
        }
        restartFlow()
        return START_NOT_STICKY
    }

    /** 버튼을 누를 때마다 이전 흐름을 버리고 처음부터 다시 */
    private fun restartFlow() {
        val gen = synchronized(genLock) { ++flowGen }
        Diag.i("ADB", "연결 흐름 시작 (gen=$gen)")
        running = true
        ok = false
        alertedPort = -1
        codes.clear()
        cancelAlert()
        goForeground("시작 중…")
        disc?.stop()
        disc = AdbDiscovery(this) { ep -> onPairingEndpoint(gen, ep) }.also { it.start() }
        thread(name = "jx11-adb-flow-$gen") {
            try {
                flow(gen)
            } catch (t: Throwable) {
                Diag.e("ADB", "연결 마법사 오류", t)
                if (gen == flowGen) setState("오류: ${t.message}")
            } finally {
                if (gen == flowGen) {
                    disc?.stop()
                    running = false
                    if (!ok) cancelAlert()
                    stopSelf()
                }
            }
        }
    }

    override fun onDestroy() {
        synchronized(genLock) { flowGen++ }
        disc?.stop()
        super.onDestroy()
    }

    /** 설정의 '페어링 코드' 창이 뜨면 팝업 알림, 닫히면 정리 (같은 포트로 여러 번 resolve 되므로 포트가 바뀔 때만) */
    private fun onPairingEndpoint(gen: Int, ep: AdbDiscovery.Ep?) {
        if (gen != flowGen) return
        if (ep == null) {
            cancelAlert()
            alertedPort = -1
            return
        }
        if (alertedPort != ep.port) {
            alertedPort = ep.port
            alertCodeReady()
        }
    }

    private fun alive(gen: Int) = gen == flowGen

    private fun waitFor(gen: Int, timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end && alive(gen)) {
            if (cond()) return true
            Thread.sleep(300)
        }
        return cond()
    }

    private fun flow(gen: Int) {
        // 1) 이미 최신 서버가 떠 있나?
        setState("기존 서버 확인 중…")
        if (AdbBridge.probe() == SocketInput.Result.OK) {
            done("이미 서버가 실행 중입니다")
            return
        }

        // 2) 이전에 페어링했다면 연결 포트만으로 기동
        setState("무선 디버깅 연결 포트 찾는 중… (무선 디버깅을 켜 두세요)")
        if (waitFor(gen, 6000) { disc?.connect != null }) {
            val ep = disc!!.connect!!
            setState("서버 기동 중… (포트 ${ep.port})")
            val err = AdbBridge.launchServer(this, ep.host, ep.port)
            if (err == null) {
                done("연결 완료 ✓ · 이제 무선 디버깅을 꺼도 됩니다 (재부팅 전까지)")
                return
            }
            Diag.w("ADB", "연결 포트로 바로 기동 실패 → 페어링 필요할 수 있음: $err")
        }
        if (!alive(gen)) return

        // 3) 페어링: 상태 알림에 처음부터 [코드 입력] 이 달려 있음. 포트를 못 찾으면 '포트 코드' 로도 입력 가능
        setState("'페어링 코드로 기기 페어링'을 누르고, 알림의 [코드 입력]에 6자리 코드를 입력하세요")
        val end = System.currentTimeMillis() + 5 * 60_000L
        while (System.currentTimeMillis() < end && alive(gen)) {
            val raw = codes.poll(1, TimeUnit.SECONDS) ?: continue
            val parsed = parsePairInput(raw)
            if (parsed == null) {
                setState("6자리 숫자를 입력해주세요 (포트가 안 잡히면 '37755 123456' 형식)")
                alertCodeReady("입력 형식 오류", "6자리 코드를 다시 입력하세요")
                continue
            }
            val cur = disc?.pairing
            val port = parsed.first ?: cur?.port
            if (port == null) {
                setState("페어링 화면을 못 찾았어요. '페어링 코드로 기기 페어링'을 먼저 열어주세요")
                alertCodeReady("페어링 화면을 못 찾았어요", "'페어링 코드로 기기 페어링'을 연 뒤 다시 입력하세요")
                continue
            }
            setState("ADB 페어링 중…")
            try {
                AdbBridge.pair(this, "127.0.0.1", port, parsed.second)
            } catch (t: Throwable) {
                Diag.e("ADB", "페어링 실패", t)
                alertedPort = -1
                setState("페어링 실패 · 코드를 다시 입력하세요")
                alertCodeReady("페어링 실패", "코드를 다시 확인하고 입력하세요")
                continue
            }
            setState("페어링 성공 · 연결 포트 검색 중…")
            var cport: Int? = null
            if (waitFor(gen, 15000) { disc?.connect != null }) {
                cport = disc!!.connect!!.port
            } else {
                setState("페어링 완료! 무선 디버깅 화면 위쪽 'IP 주소 및 포트'의 포트(5자리)를 입력하세요")
                alertCodeReady("연결 포트가 필요해요", "무선 디버깅 화면의 포트(5자리)를 입력하세요")
                val e2 = System.currentTimeMillis() + 120_000L
                while (System.currentTimeMillis() < e2 && alive(gen) && cport == null) {
                    val ce = disc?.connect
                    if (ce != null) {
                        cport = ce.port
                        break
                    }
                    val t = codes.poll(1, TimeUnit.SECONDS) ?: continue
                    cport = Regex("(\\d{4,5})\\s*$").find(t.trim())?.groupValues?.get(1)?.toIntOrNull()
                }
            }
            if (cport == null) {
                setState("연결 포트를 못 찾음 — [무선 ADB 연결]을 다시 눌러 주세요 (페어링은 완료됨)")
                return
            }
            setState("서버 기동 중…")
            val err = AdbBridge.launchServer(this, "127.0.0.1", cport)
            if (err == null) {
                done("연결 완료 ✓ · 이제 무선 디버깅을 꺼도 됩니다 (재부팅 전까지)")
                return
            }
            setState("서버 기동 실패: $err")
            return
        }
        if (alive(gen)) setState("시간 초과 (5분)")
    }

    /** "123456" → (null, 코드) / "37755 123456" 또는 "192.168.0.5:37755 123456" → (포트, 코드) */
    private fun parsePairInput(raw: String): Pair<Int?, String>? {
        val t = raw.trim()
        if (Regex("^\\d{6}$").matches(t)) return Pair(null, t)
        val m = Regex("^(?:(?:\\d{1,3}\\.){3}\\d{1,3}:)?(\\d{4,5})[\\s,]+(\\d{6})$").find(t) ?: return null
        return Pair(m.groupValues[1].toIntOrNull(), m.groupValues[2])
    }

    private fun done(msg: String) {
        ok = true
        setState(msg, input = false)
        Diag.i("ADB", msg)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NID_ALERT)
        KeymapService.instance?.reconnectAdb()
        nm.notify(NID_ALERT, Notification.Builder(this, CH_STATUS)
            .setContentTitle("무선 ADB").setContentText(msg)
            .setStyle(Notification.BigTextStyle().bigText(msg))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setAutoCancel(true).setTimeoutAfter(20000).build())
    }

    private fun setState(s: String, input: Boolean = true) {
        state = s
        Diag.i("ADB", "상태: $s")
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NID_STATUS, statusNotification(s, input))
        } catch (t: Throwable) {
            Diag.w("ADB", "알림 갱신 실패: ${t.message}")
        }
    }

    /** 코드 입력창(RemoteInput) 액션. 스마트 답장/시스템 추천 동작이 끼어들지 않게 막는다 */
    private fun codeInputAction(): Notification.Action {
        val ri = RemoteInput.Builder(KEY_CODE)
            .setLabel("6자리 페어링 코드")
            .build()
        val pi = PendingIntent.getService(
            this, 2001, Intent(this, AdbPairingService::class.java).setAction(ACTION_CODE),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        @Suppress("DEPRECATION")
        return Notification.Action.Builder(android.R.drawable.ic_input_add, "코드 입력", pi)
            .addRemoteInput(ri)
            .setAllowGeneratedReplies(false)
            .setSemanticAction(Notification.Action.SEMANTIC_ACTION_NONE)
            .build()
    }

    private fun statusNotification(text: String, input: Boolean): Notification {
        val stop = PendingIntent.getService(
            this, 3, Intent(this, AdbPairingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, CH_STATUS)
            .setContentTitle("무선 ADB 연결")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))   // 긴 상태 문구도 다 보이게
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setAllowSystemGeneratedContextualActions(false)
        if (input) b.addAction(codeInputAction())
        @Suppress("DEPRECATION")
        b.addAction(0, "취소", stop)
        return b.build()
    }

    private fun goForeground(text: String) {
        state = text
        val n = statusNotification(text, true)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NID_STATUS, n)
        }
    }

    /**
     * 페어링 코드 창이 뜬 순간 별도 알림을 헤드업(팝업)으로 띄운다. 상시 알림은 내용만 바뀌고 팝업이 안 뜨기 때문.
     * 헤드업에서 바로 [코드 입력]을 누르면 알림창 전체를 내리지 않아 코드가 덜 가려진다.
     * 문구에 숫자를 넣지 않는다 (삼성이 '복사' 버튼을 붙임).
     */
    private fun alertCodeReady(title: String = "페어링 코드가 떴어요", text: String = "설정 화면의 코드를 입력하세요") {
        try {
            val n = Notification.Builder(this, CH_ALERT)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setTimeoutAfter(120_000)
                .setAllowSystemGeneratedContextualActions(false)
                .addAction(codeInputAction())
                .build()
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NID_ALERT, n)
            Diag.i("ADB", "팝업 알림 표시: $title")
        } catch (t: Throwable) {
            Diag.e("ADB", "팝업 알림 실패", t)
        }
    }

    private fun cancelAlert() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NID_ALERT)
    }
}
