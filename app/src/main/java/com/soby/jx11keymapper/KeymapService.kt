package com.soby.jx11keymapper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import rikka.shizuku.Shizuku
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** 포그라운드 서비스: Shizuku UserService에 붙어 이벤트를 받고, 엔진을 거쳐 볼륨을 조절한다. */
class KeymapService : Service() {

    companion object {
        const val ACTION_STOP = "com.soby.jx11keymapper.STOP"
        const val BACKEND_AUTO = 0
        const val BACKEND_SHIZUKU = 1
        const val BACKEND_ADB = 2
        private const val CHANNEL = "keymap"
        private const val NID = 1

        @Volatile var instance: KeymapService? = null
        @Volatile var status: String = "꺼짐"
        @Volatile var devices: String = ""
        @Volatile var eventCount: Long = 0L
        @Volatile var lastEvent: String = "-"

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, KeymapService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, KeymapService::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var input: IInputService? = null
    private var bound = false
    private var started = false
    private var wl: PowerManager.WakeLock? = null

    // 무선 ADB 방식
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private var adbClient: SocketInput? = null
    @Volatile private var adbConnecting = false
    private var via = "Shizuku"

    // 동작 실행은 별도 단일 스레드에서 순서대로 (셸 호출이 이벤트 수신을 막지 않도록)
    private val exec: ExecutorService = Executors.newSingleThreadExecutor()

    val engine = Engine { act, n ->
        exec.execute { ActionRunner.run(applicationContext, act, n, input) }
    }

    private val userArgs: Shizuku.UserServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(packageName, InputService::class.java.name))
            .processNameSuffix("input")
            .debuggable(false)
            .daemon(false)
            .version(BuildConfig.VERSION_CODE)
    }

    private val callback = object : IEventCallback.Stub() {
        override fun onEvent(type: Int, code: Int, value: Int, device: String) {
            handleEvent(type, code, value, device)
        }

        override fun onStatus(level: String, stage: String, msg: String) {
            val lv = if (level.isNotEmpty()) level[0] else 'I'
            Diag.log(lv, "SVC.$stage", msg)
        }

        override fun onDevices(summary: String) {
            devices = summary
        }
    }

    private val onBinder = Shizuku.OnBinderReceivedListener {
        Diag.i("SHIZUKU", "binder 수신")
        main.post { tryBind() }
    }

    private val onDead = Shizuku.OnBinderDeadListener {
        Diag.w("SHIZUKU", "binder 종료됨")
        bound = false
        input = null
        setStatus("Shizuku 종료됨 — 다시 시작하면 자동 재연결")
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            try {
                if (binder == null || !binder.pingBinder()) {
                    Diag.e("BIND", "UserService binder 무효")
                    bound = false
                    return
                }
                val svc = IInputService.Stub.asInterface(binder)
                input = svc
                Diag.i("BIND", "UserService 연결됨: ${svc.ping()}")
                try { svc.setGrab(Cfg.grab) } catch (t: Throwable) { Diag.w("SVC", "setGrab 실패(구버전 서비스?): ${t.message}") }
                try { svc.setImeWithHardKeyboard(Cfg.imeWithKeyboard) } catch (t: Throwable) { Diag.w("SVC", "화면 키보드 설정 실패(구버전 서비스?): ${t.message}") }
                svc.start(Cfg.deviceFilter, callback)
                via = "Shizuku"
                setStatus("동작 중 · Shizuku (장치 필터 '${Cfg.deviceFilter}')")
            } catch (t: Throwable) {
                Diag.e("BIND", "UserService 시작 실패", t)
                setStatus("서비스 시작 실패: ${t.message}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Diag.w("BIND", "UserService 연결 끊김")
            input = null
            bound = false
            setStatus("서비스 끊김 — 재연결 대기")
            main.postDelayed({ if (started) tryBind() }, 2000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Diag.i("SVC", "onCreate")
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "키맵퍼 동작 상태", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Diag.i("SVC", "알림에서 중지")
            Cfg.enabled = false
            Cfg.save()
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        if (!started) {
            started = true
            Cfg.enabled = true
            Cfg.save()
            applyWake()
            val b = Cfg.backend
            Diag.i("SVC", "연결 방식: ${when (b) { BACKEND_SHIZUKU -> "Shizuku"; BACKEND_ADB -> "무선 ADB"; else -> "자동" }}")
            if (b != BACKEND_ADB) {
                try {
                    Shizuku.addBinderReceivedListenerSticky(onBinder)
                    Shizuku.addBinderDeadListener(onDead)
                } catch (t: Throwable) {
                    Diag.e("SHIZUKU", "리스너 등록 실패", t)
                }
                setStatus("Shizuku 연결 확인 중…")
            }
            if (b == BACKEND_ADB) {
                connectAdb()
            } else if (b == BACKEND_AUTO) {
                // Shizuku 가 2초 안에 안 붙으면 무선 ADB 서버로 시도
                main.postDelayed({ if (started && input == null && !bound) connectAdb() }, 2000)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Diag.i("SVC", "onDestroy")
        started = false
        try {
            Shizuku.removeBinderReceivedListener(onBinder)
            Shizuku.removeBinderDeadListener(onDead)
        } catch (t: Throwable) {
            Diag.w("SHIZUKU", "리스너 해제 실패: ${t.message}")
        }
        try {
            input?.stop()
        } catch (t: Throwable) {
            Diag.w("BIND", "stop 호출 실패: ${t.message}")
        }
        try {
            Shizuku.unbindUserService(userArgs, conn, true)
        } catch (t: Throwable) {
            Diag.w("BIND", "unbind 실패: ${t.message}")
        }
        try {
            adbClient?.close()   // 서버는 그대로 둔다 (다음에 바로 재접속)
        } catch (t: Throwable) {
            Diag.w("ADB", "클라이언트 닫기 실패: ${t.message}")
        }
        adbClient = null
        input = null
        bound = false
        io.shutdownNow()
        exec.shutdownNow()
        try {
            if (wl?.isHeld == true) wl?.release()
        } catch (t: Throwable) {
            Diag.w("SVC", "wakelock 해제 실패: ${t.message}")
        }
        wl = null
        status = "꺼짐"
        devices = ""
        instance = null
        super.onDestroy()
    }

    // ───────── Shizuku 바인딩 ─────────

    fun onPermissionChanged() {
        main.post { tryBind() }
    }

    private fun tryBind() {
        if (!started) return
        if (adbClient != null) return   // 이미 무선 ADB 로 연결됨
        try {
            if (!Shizuku.pingBinder()) {
                setStatus("Shizuku 실행 안 됨 — Shizuku 앱에서 시작하세요")
                return
            }
            if (Shizuku.isPreV11()) {
                setStatus("Shizuku 버전이 너무 낮음 (11 이상 필요)")
                return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                setStatus("Shizuku 권한 필요 — 앱에서 [Shizuku 권한 요청]")
                return
            }
            if (bound) return
            Diag.i("BIND", "bindUserService 시도 (Shizuku v${Shizuku.getVersion()}, uid=${Shizuku.getUid()})")
            bound = true
            Shizuku.bindUserService(userArgs, conn)
            setStatus("UserService 연결 중…")
        } catch (t: Throwable) {
            bound = false
            Diag.e("BIND", "bind 실패", t)
            setStatus("연결 실패: ${t.message}")
            main.postDelayed({ tryBind() }, 3000)
        }
    }

    // ───────── 무선 ADB 서버 접속 ─────────

    fun reconnectAdb() {
        main.post { connectAdb() }
    }

    private fun connectAdb() {
        if (!started || adbConnecting || input != null) return
        if (Cfg.backend == BACKEND_SHIZUKU) return
        adbConnecting = true
        io.execute {
            try {
                if (Cfg.adbPort == 0 || Cfg.adbToken.isEmpty()) {
                    setStatus("무선 ADB 서버 없음 — 앱에서 [무선 ADB 연결]을 누르세요")
                    retryAdb(5000)
                    return@execute
                }
                val c = SocketInput(Cfg.adbPort, Cfg.adbToken) { why -> main.post { onAdbClosed(why) } }
                when (val r = c.connect()) {
                    SocketInput.Result.OK -> {
                        c.setGrab(Cfg.grab)
                        c.setImeWithHardKeyboard(Cfg.imeWithKeyboard)
                        adbClient = c
                        input = c
                        c.start(Cfg.deviceFilter, callback)
                        via = "무선 ADB"
                        Diag.i("ADB", "서버 연결됨: ${c.ping()}")
                        setStatus("동작 중 · 무선 ADB (장치 필터 '${Cfg.deviceFilter}')")
                    }
                    SocketInput.Result.OLD_VERSION -> {
                        Diag.w("ADB", "서버 버전 ${c.serverVersion} ≠ 앱 ${BuildConfig.VERSION_CODE} → 서버 종료")
                        c.destroy()
                        setStatus("무선 ADB 서버가 구버전 — 앱에서 [무선 ADB 연결]로 새로 띄우세요")
                    }
                    else -> {
                        Diag.w("ADB", "서버 접속 실패 $r: ${c.lastError}")
                        setStatus(
                            if (r == SocketInput.Result.NO_SERVER) "무선 ADB 서버가 꺼져 있음(재부팅 후?) — 앱에서 [무선 ADB 연결]"
                            else "무선 ADB 서버 접속 실패: ${c.lastError}"
                        )
                        retryAdb(5000)
                    }
                }
            } catch (t: Throwable) {
                Diag.e("ADB", "서버 접속 중 오류", t)
                retryAdb(5000)
            } finally {
                adbConnecting = false
            }
        }
    }

    private fun retryAdb(ms: Long) {
        main.postDelayed({ if (started && input == null && !bound) connectAdb() }, ms)
    }

    private fun onAdbClosed(why: String) {
        if (adbClient == null) return
        Diag.w("ADB", "서버 연결 끊김: $why")
        adbClient = null
        input = null
        setStatus("무선 ADB 서버 연결 끊김 — 재접속 시도")
        retryAdb(3000)
    }

    /** 물리 키보드 연결 중 화면 키보드 표시 설정을 셸 권한으로 적용 */
    fun applyIme() {
        val svc = input ?: return
        io.execute {
            try { svc.setImeWithHardKeyboard(Cfg.imeWithKeyboard) } catch (t: Throwable) { Diag.w("SVC", "화면 키보드 설정 실패: ${t.message}") }
        }
    }

    fun restartInput() {
        val svc = input
        if (svc == null) {
            Diag.w("SVC", "restartInput: UserService 미연결")
            return
        }
        try {
            svc.stop()
            try { svc.setGrab(Cfg.grab) } catch (t: Throwable) { Diag.w("SVC", "setGrab 실패(구버전 서비스?): ${t.message}") }
                svc.start(Cfg.deviceFilter, callback)
            Diag.i("SVC", "장치 필터 적용: '${Cfg.deviceFilter}'")
            setStatus("동작 중 · $via (장치 필터 '${Cfg.deviceFilter}')")
        } catch (t: Throwable) {
            Diag.e("SVC", "restartInput 실패", t)
        }
    }

    /** 전체 입력장치 목록 (이름 확인용). 백그라운드 스레드에서 호출할 것 */
    fun listAllDevices(): String {
        return try {
            input?.listDevices() ?: ""
        } catch (t: Throwable) {
            Diag.e("SVC", "장치 목록 실패", t)
            ""
        }
    }

    fun applyWake() {
        try {
            if (Cfg.wakeLock) {
                if (wl == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jx11:keymap").apply { setReferenceCounted(false) }
                }
                if (wl?.isHeld != true) wl?.acquire()
                Diag.i("SVC", "wakelock ON")
            } else {
                if (wl?.isHeld == true) wl?.release()
                Diag.i("SVC", "wakelock OFF")
            }
        } catch (t: Throwable) {
            Diag.e("SVC", "wakelock 처리 실패", t)
        }
    }

    // ───────── 이벤트 처리 ─────────

    private fun handleEvent(type: Int, code: Int, value: Int, dev: String) {
        try {
            eventCount++
            lastEvent = "$dev · ${EventNames.typeName(type)} ${EventNames.codeName(type, code)} $value"
            Diag.v("EVENT", lastEvent)
            if (Learner.offer(type, code, value)) return
            val hit = Mapper.classify(type, code, value) ?: return
            Diag.v("MAP", "${hit.act.label} 가중치 ${hit.weight}")
            engine.feed(hit)
        } catch (t: Throwable) {
            Diag.e("MAP", "이벤트 처리 오류", t)
        }
    }

    // ───────── 알림 ─────────

    private fun setStatus(s: String) {
        status = s
        Diag.i("STATUS", s)
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NID, buildNotification(s))
        } catch (t: Throwable) {
            Diag.w("SVC", "알림 갱신 실패: ${t.message}")
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, KeymapService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("JX-11 키맵퍼")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .addAction(0, "중지", stop)
            .setOngoing(true)
            .build()
    }

    private fun goForeground() {
        val n = buildNotification(status)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NID, n)
        }
    }
}
