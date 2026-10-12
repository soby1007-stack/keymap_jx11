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

/** mDNS 로 무선 디버깅의 페어링 포트/연결 포트를 찾는다 */
class AdbDiscovery(ctx: Context) {
    data class Ep(val host: String, val port: Int, val name: String)

    @Volatile var pairing: Ep? = null
    @Volatile var connect: Ep? = null

    private val nsd = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val queue = LinkedBlockingQueue<NsdServiceInfo>()
    private val listeners = ArrayList<NsdManager.DiscoveryListener>()
    @Volatile private var running = false

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
                            val host = si.host?.hostAddress?.substringBefore('%')
                            if (host != null) {
                                val ep = Ep(host, si.port, si.serviceName)
                                if (si.serviceType.contains("pairing")) pairing = ep else connect = ep
                                Diag.i("ADB", "mDNS ${if (si.serviceType.contains("pairing")) "페어링" else "연결"} 포트 발견: $host:${si.port}")
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
        for (type in listOf("_adb-tls-pairing._tcp", "_adb-tls-connect._tcp")) {
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
                        if (pairing?.name == si.serviceName) pairing = null
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
 * 3) 아니면 페어링 포트를 찾아 알림에 6자리 코드 입력창을 띄움 → 페어링 → 서버 기동
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
    @Volatile private var cancelled = false
    @Volatile private var ok = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "무선 ADB 연결 상태", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "무선 ADB 페어링 코드 입력", NotificationManager.IMPORTANCE_HIGH))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                cancelled = true
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CODE -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()?.trim()
                if (!code.isNullOrEmpty()) {
                    Diag.i("ADB", "알림에서 페어링 코드 입력됨")
                    codes.offer(code)
                    notifyAlert("코드 확인 중…", false, null)
                }
                return START_NOT_STICKY
            }
        }
        if (running) return START_NOT_STICKY
        running = true
        cancelled = false
        goForeground("시작 중…")
        disc = AdbDiscovery(this).also { it.start() }
        thread(name = "jx11-adb-flow") {
            try {
                flow()
            } catch (t: Throwable) {
                Diag.e("ADB", "연결 마법사 오류", t)
                setState("오류: ${t.message}")
            } finally {
                disc?.stop()
                running = false
                if (!ok) (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NID_ALERT)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancelled = true
        disc?.stop()
        super.onDestroy()
    }

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end && !cancelled) {
            if (cond()) return true
            Thread.sleep(300)
        }
        return cond()
    }

    private fun flow() {
        // 1) 이미 서버가 떠 있나?
        setState("기존 서버 확인 중…")
        if (Cfg.adbPort != 0 && Cfg.adbToken.isNotEmpty()) {
            val c = SocketInput(Cfg.adbPort, Cfg.adbToken)
            val r = c.connect()
            c.close()
            if (r == SocketInput.Result.OK) {
                done("이미 서버가 실행 중입니다")
                return
            }
            Diag.i("ADB", "기존 서버 상태: $r")
        }

        // 2) 이전에 페어링했다면 연결 포트만으로 기동
        setState("무선 디버깅 연결 포트 찾는 중… (설정에서 무선 디버깅을 켜세요)")
        if (waitFor(6000) { disc?.connect != null }) {
            val ep = disc!!.connect!!
            setState("서버 기동 중… (${ep.host}:${ep.port})")
            val err = AdbBridge.launchServer(this, ep.host, ep.port)
            if (err == null) {
                done("연결 완료 — 서버 실행됨")
                return
            }
            Diag.w("ADB", "연결 포트로 바로 기동 실패 → 페어링 필요할 수 있음: $err")
        }

        // 3) 페어링: 알림에 코드 입력창
        setState("무선 디버깅 > '페어링 코드로 기기 페어링' 을 누르세요")
        val end = System.currentTimeMillis() + 4 * 60_000L
        while (System.currentTimeMillis() < end && !cancelled) {
            val pe = disc?.pairing
            if (pe == null) {
                notifyAlert("설정 > 무선 디버깅 > '페어링 코드로 기기 페어링' 을 누르세요", false, null)
                Thread.sleep(500)
                continue
            }
            notifyAlert("알림 아래의 [코드 입력]을 눌러 6자리 코드를 입력하세요", true, pe)
            setState("페어링 코드를 알림에 입력하세요")
            val code = codes.poll(1, TimeUnit.SECONDS) ?: continue
            if (disc?.pairing == null) {
                notifyAlert("페어링 창이 닫혔어요. 다시 '페어링 코드로 기기 페어링'을 누르세요", false, null)
                continue
            }
            setState("페어링 중…")
            try {
                AdbBridge.pair(this, pe.host, pe.port, code)
            } catch (t: Throwable) {
                Diag.e("ADB", "페어링 실패", t)
                notifyAlert("페어링 실패 (코드 확인): ${t.message}", false, null)
                setState("페어링 실패 — 다시 시도")
                Thread.sleep(1500)
                continue
            }
            setState("페어링 완료 — 연결 포트 대기 중…")
            if (!waitFor(15000) { disc?.connect != null }) {
                setState("연결 포트를 못 찾음 — 무선 디버깅이 켜져 있는지 확인")
                notifyAlert("연결 포트를 못 찾았어요. 무선 디버깅 화면을 열어 둔 채 다시 시도", false, null)
                continue
            }
            val ce = disc!!.connect!!
            setState("서버 기동 중…")
            val err = AdbBridge.launchServer(this, ce.host, ce.port)
            if (err == null) {
                done("페어링·연결 완료 — 서버 실행됨")
                return
            }
            setState("서버 기동 실패: $err")
            notifyAlert("서버 기동 실패: $err", false, null)
            return
        }
        setState(if (cancelled) "취소됨" else "시간 초과 (4분)")
    }

    private fun done(msg: String) {
        ok = true
        setState(msg)
        Diag.i("ADB", msg)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NID_ALERT)
        KeymapService.instance?.reconnectAdb()
        nm.notify(NID_ALERT, Notification.Builder(this, CH_STATUS)
            .setContentTitle("무선 ADB").setContentText(msg)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setAutoCancel(true).setTimeoutAfter(15000).build())
    }

    private fun setState(s: String) {
        state = s
        Diag.i("ADB", "상태: $s")
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NID_STATUS, statusNotification(s))
        } catch (t: Throwable) {
            Diag.w("ADB", "알림 갱신 실패: ${t.message}")
        }
    }

    private fun statusNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 3, Intent(this, AdbPairingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CH_STATUS)
            .setContentTitle("무선 ADB 연결")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .addAction(0, "취소", stop)
            .setOngoing(true)
            .build()
    }

    private fun goForeground(text: String) {
        val n = statusNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NID_STATUS, n)
        }
    }

    /** 코드 입력 알림 (RemoteInput). 스마트 답장/숫자 복사 버튼이 끼어들지 않게 막는다 */
    private fun notifyAlert(text: String, withInput: Boolean, ep: AdbDiscovery.Ep?) {
        val b = Notification.Builder(this, CH_ALERT)
            .setContentTitle("무선 ADB 페어링")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOnlyAlertOnce(true)
            .setAllowSystemGeneratedContextualActions(false)
        if (withInput) {
            val ri = RemoteInput.Builder(KEY_CODE).setLabel("6자리 코드").build()
            val pi = PendingIntent.getService(
                this, 2, Intent(this, AdbPairingService::class.java).setAction(ACTION_CODE),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            @Suppress("DEPRECATION")
            val action = Notification.Action.Builder(android.R.drawable.ic_menu_edit, "코드 입력", pi)
                .addRemoteInput(ri)
                .setAllowGeneratedReplies(false)
                .build()
            b.addAction(action)
        }
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NID_ALERT, b.build())
        } catch (t: Throwable) {
            Diag.w("ADB", "코드 입력 알림 실패: ${t.message}")
        }
    }
}
