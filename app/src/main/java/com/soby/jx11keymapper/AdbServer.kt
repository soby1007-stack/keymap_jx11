package com.soby.jx11keymapper

import android.system.Os
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * 무선 ADB 방식의 서버. 앱이 Kadb 로 adb shell 에 접속해 `app_process` 로 띄운다 (shell uid 2000).
 * Shizuku 의 UserService 와 같은 일(InputService)을 하되, Binder 대신 127.0.0.1 소켓으로 앱과 통신한다.
 * 한 번 띄워 두면 Wi-Fi 가 꺼져도 재부팅 전까지 살아 있다.
 *
 * 보안: 127.0.0.1 한정 + 랜덤 토큰 인증 + 허용 명령 7개뿐 (임의 명령 실행 불가)
 * 프로토콜(탭 구분, 한 줄 한 메시지):
 *   앱→서버  HELLO token ver | START filter grab | STOP | LIST | PING | KEY code | NOTIF | QUIT
 *   서버→앱  OK ver ping | ERR why | E type code value device | S level stage msg | D summary | R payload | BYE
 */
object AdbServer {
    private val lock = Any()
    private var current: Socket? = null

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            run(args)
        } catch (t: Throwable) {
            System.err.println("FATAL " + chain(t))
            exitProcess(1)
        }
    }

    private fun run(args: Array<String>) {
        val port = args[0].toInt()
        // 토큰은 환경변수로 받는다 (명령줄에 안 남김). 인자도 허용
        val token = System.getenv("JX11_TOKEN")?.takeIf { it.length >= 16 } ?: args.getOrNull(1) ?: error("토큰 없음")
        val svc = InputService()
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
        println("JX11 서버 시작 port=$port ver=${BuildConfig.VERSION_CODE} uid=${Os.getuid()}")
        while (true) {
            val s = ss.accept()
            synchronized(lock) {
                try {
                    current?.close()   // 새 클라이언트가 이전 연결을 대체
                } catch (e: Throwable) {
                    // 무시
                }
                current = s
            }
            thread(name = "jx11-client") { handle(s, token, svc) }
        }
    }

    private fun handle(s: Socket, token: String, svc: InputService) {
        try {
            val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val w = PrintWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8), false)
            fun send(line: String) {
                synchronized(w) {
                    w.print(line)
                    w.print('\n')
                    w.flush()
                }
            }
            s.soTimeout = 5000
            val hello = (r.readLine() ?: return).split("\t")
            if (hello.size < 3 || hello[0] != "HELLO" || hello[1] != token) {
                send("ERR\ttoken")
                s.close()
                return
            }
            send("OK\t${BuildConfig.VERSION_CODE}\t${esc(svc.ping())}")
            s.soTimeout = 0

            val cb = object : IEventCallback.Stub() {
                override fun onEvent(type: Int, code: Int, value: Int, device: String) {
                    send("E\t$type\t$code\t$value\t${esc(device)}")
                }

                override fun onStatus(level: String, stage: String, msg: String) {
                    send("S\t$level\t$stage\t${esc(msg)}")
                }

                override fun onDevices(summary: String) {
                    send("D\t${esc(summary)}")
                }
            }

            while (true) {
                val line = r.readLine() ?: break
                val p = line.split("\t")
                when (p[0]) {
                    "START" -> {
                        svc.setGrab(p.getOrNull(2) == "1")
                        svc.start(p.getOrElse(1) { "" }, cb)
                    }
                    "STOP" -> svc.stop()
                    "LIST" -> send("R\t" + esc(svc.listDevices()))
                    "PING" -> send("R\tpong")
                    "KEY" -> svc.sendKey(p.getOrElse(1) { "0" }.toInt())
                    "NOTIF" -> svc.expandNotifications()
                    "QUIT" -> {
                        svc.stop()
                        send("BYE")
                        exitProcess(0)
                    }
                    else -> send("S\tW\tSERVER\t알 수 없는 명령: ${esc(p[0])}")
                }
            }
        } catch (t: Throwable) {
            System.err.println("client 오류 " + chain(t))
        } finally {
            synchronized(lock) {
                if (current === s) {
                    try {
                        svc.stop()   // 앱이 사라지면 읽기 중단 (독점도 해제됨). 서버는 계속 대기
                    } catch (e: Throwable) {
                        // 무시
                    }
                    current = null
                }
            }
            try {
                s.close()
            } catch (e: Throwable) {
                // 무시
            }
        }
    }

    fun esc(s: String): String = s.replace("\t", " ").replace("\r", "").replace("\n", "⏎")

    fun unesc(s: String): String = s.replace("⏎", "\n")

    private fun chain(t: Throwable): String {
        val sb = StringBuilder()
        var cur: Throwable? = t
        var d = 0
        while (cur != null && d < 8) {
            if (d > 0) sb.append(" <= ")
            sb.append(cur.javaClass.name).append(": ").append(cur.message)
            cur = cur.cause
            d++
        }
        return sb.toString()
    }
}
