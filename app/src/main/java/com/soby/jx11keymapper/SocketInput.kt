package com.soby.jx11keymapper

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 무선 ADB 서버(AdbServer)와 소켓으로 통신하는 앱 쪽 클라이언트.
 * IInputService 를 그대로 구현해서 Shizuku 방식(바인더)과 똑같이 쓸 수 있다.
 */
class SocketInput(
    private val port: Int,
    private val token: String,
    private val onClosed: (String) -> Unit = {}
) : IInputService.Stub() {

    enum class Result { OK, NO_SERVER, BAD_TOKEN, OLD_VERSION, ERROR }

    @Volatile var serverVersion = 0
    @Volatile var lastError = ""

    private var sock: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var pingStr = ""

    @Volatile private var cb: IEventCallback? = null
    @Volatile private var grab = false
    @Volatile private var closing = false
    private val resp = LinkedBlockingQueue<String>()

    /** 접속+인증. OLD_VERSION 이면 소켓을 열어 둔 채 반환 (호출자가 destroy() 로 서버를 종료시킬 수 있음) */
    fun connect(): Result {
        try {
            val s = Socket()
            s.connect(InetSocketAddress("127.0.0.1", port), 1500)
            s.soTimeout = 3000
            sock = s
            writer = PrintWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8), false)
            reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            send("HELLO\t$token\t${BuildConfig.VERSION_CODE}")
            val line = reader?.readLine()
            if (line == null) {
                lastError = "서버가 응답 없이 연결을 닫음"
                close()
                return Result.ERROR
            }
            if (line.startsWith("ERR")) {
                lastError = "토큰 불일치"
                close()
                return Result.BAD_TOKEN
            }
            val p = line.split("\t")
            serverVersion = p.getOrElse(1) { "0" }.toIntOrNull() ?: 0
            pingStr = AdbServer.unesc(p.getOrElse(2) { "" })
            s.soTimeout = 0
            if (serverVersion != BuildConfig.VERSION_CODE) return Result.OLD_VERSION
            startReader()
            return Result.OK
        } catch (e: ConnectException) {
            lastError = "서버 없음 (포트 $port): ${e.message}"
            close()
            return Result.NO_SERVER
        } catch (t: Throwable) {
            lastError = Diag.chain(t)
            close()
            return Result.ERROR
        }
    }

    private fun startReader() {
        thread(name = "jx11-sock-read") {
            var why = "연결 종료"
            try {
                val r = reader ?: return@thread
                while (true) {
                    val line = r.readLine() ?: break
                    val p = line.split("\t", limit = 5)
                    when (p[0]) {
                        "E" -> if (p.size >= 5) {
                            cb?.onEvent(p[1].toInt(), p[2].toInt(), p[3].toInt(), AdbServer.unesc(p[4]))
                        }
                        "S" -> if (p.size >= 4) {
                            cb?.onStatus(p[1], p[2], AdbServer.unesc(line.split("\t", limit = 4)[3]))
                        }
                        "D" -> cb?.onDevices(AdbServer.unesc(line.substringAfter("\t", "")))
                        "R" -> resp.offer(AdbServer.unesc(line.substringAfter("\t", "")))
                        "BYE" -> {
                            why = "서버가 종료됨"
                            break
                        }
                    }
                }
            } catch (t: Throwable) {
                why = if (closing) "닫음" else Diag.chain(t)
            }
            if (!closing) {
                closing = true
                try { sock?.close() } catch (e: Throwable) { /* 무시 */ }
                onClosed(why)
            }
        }
    }

    private val sender = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun send(line: String) {
        // 메인 스레드에서 소켓에 쓰면 예외가 나므로 전용 스레드로 넘긴다
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            try { sender.execute { sendNow(line) } } catch (e: Throwable) { Diag.w("ADB", "전송 큐 거부: $line") }
        } else {
            sendNow(line)
        }
    }

    private fun sendNow(line: String) {
        val w = writer ?: return
        synchronized(w) {
            w.print(line)
            w.print('\n')
            w.flush()
            if (w.checkError()) Diag.w("ADB", "서버로 전송 실패: $line")
        }
    }

    fun close() {
        closing = true
        try { sock?.close() } catch (e: Throwable) { /* 무시 */ }
    }

    // ───────── IInputService ─────────

    override fun destroy() {
        send("QUIT")
        close()
    }

    override fun start(nameFilter: String, callback: IEventCallback) {
        cb = callback
        send("START\t${AdbServer.esc(nameFilter)}\t${if (grab) 1 else 0}")
    }

    override fun stop() = send("STOP")

    override fun listDevices(): String {
        resp.clear()
        send("LIST")
        return resp.poll(5, TimeUnit.SECONDS) ?: ""
    }

    override fun ping(): String = pingStr

    override fun sendKey(keyCode: Int) = send("KEY\t$keyCode")

    override fun expandNotifications() = send("NOTIF")

    override fun setGrab(on: Boolean) {
        grab = on
    }

    override fun setImeWithHardKeyboard(show: Boolean) = send("IME\t${if (show) 1 else 0}")

    override fun grantUsageAccess(pkg: String) = send("USAGE\t${AdbServer.esc(pkg)}")
}
