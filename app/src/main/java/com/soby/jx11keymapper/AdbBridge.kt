package com.soby.jx11keymapper

import android.content.Context
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.SecureRandom

/** 무선 ADB(Kadb) 연결 도우미: 키 보관, 페어링, shell 권한 서버(AdbServer) 기동 */
object AdbBridge {
    private const val SERVER_LOG = "/data/local/tmp/jx11_server.log"
    private const val PAIR_NAME = "JX11Keymapper"

    /** Kadb 인증서/키를 앱 저장소에 보관 → 앱을 다시 켜도 같은 키로 접속 (재페어링 불필요) */
    @Synchronized
    fun initKey(ctx: Context) {
        val dir = ctx.noBackupFilesDir
        val cf = File(dir, "adb_cert.bin")
        val kf = File(dir, "adb_key.bin")
        if (cf.exists() && kf.exists()) {
            try {
                KadbCert.set(cf.readBytes(), kf.readBytes())
                Diag.d("ADB", "저장된 ADB 키 로드")
                return
            } catch (t: Throwable) {
                Diag.w("ADB", "저장된 키 로드 실패 → 새로 생성 (재페어링 필요): ${Diag.chain(t)}")
                cf.delete()
                kf.delete()
            }
        }
        val (c, k) = KadbCert.get()
        cf.writeBytes(c)
        kf.writeBytes(k)
        Diag.i("ADB", "새 ADB 키 생성·저장")
    }

    /** 무선 디버깅 페어링 (6자리 코드). 백그라운드 스레드에서 호출 */
    fun pair(ctx: Context, host: String, port: Int, code: String) {
        initKey(ctx)
        Diag.i("ADB", "페어링 시도 $host:$port")
        runBlocking { Kadb.pair(host, port, code, PAIR_NAME) }
        Diag.i("ADB", "페어링 성공")
    }

    private fun randomToken(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    /** 저장된 포트/토큰으로 서버 상태 확인. OK = 최신 서버가 이미 실행 중 */
    fun probe(): SocketInput.Result {
        if (Cfg.adbPort == 0 || Cfg.adbToken.isEmpty()) return SocketInput.Result.NO_SERVER
        val c = SocketInput(Cfg.adbPort, Cfg.adbToken)
        val r = c.connect()
        c.close()
        Diag.i("ADB", "기존 서버 상태: $r (포트 ${Cfg.adbPort})")
        return r
    }

    /** 떠 있는 서버를 종료시킨다 (구버전 교체/사용자가 종료 요청) */
    fun quitServer() {
        val port = Cfg.adbPort
        val token = Cfg.adbToken
        if (port == 0 || token.isEmpty()) return
        val c = SocketInput(port, token)
        val r = c.connect()
        if (r == SocketInput.Result.OK || r == SocketInput.Result.OLD_VERSION) {
            Diag.i("ADB", "서버 종료 요청 ($r)")
            c.destroy()
        } else {
            c.close()
        }
    }

    private fun ensurePortToken() {
        if (Cfg.adbToken.isEmpty()) Cfg.adbToken = randomToken()
        if (Cfg.adbPort == 0) Cfg.adbPort = 38461 + SecureRandom().nextInt(3000)
        Cfg.save()
    }

    /**
     * 연결 포트로 adb 에 접속해 shell 권한 서버를 띄운다. 성공하면 null, 실패하면 원인 문자열.
     * - 이미 최신 서버가 떠 있으면 그대로 사용 (포트/토큰은 한 번 정하면 계속 유지)
     * - 한 번의 ADB 세션으로 모든 명령 실행 (붙었다 끊었다 하면 설정의 '연결됨' 표시가 깜빡임)
     * 백그라운드 스레드에서 호출.
     */
    fun launchServer(ctx: Context, host: String, port: Int): String? {
        initKey(ctx)
        ensurePortToken()
        when (probe()) {
            SocketInput.Result.OK -> {
                Diag.i("ADB", "최신 서버가 이미 실행 중 → 그대로 사용")
                return null
            }
            SocketInput.Result.OLD_VERSION -> {
                try {
                    quitServer()
                    Thread.sleep(400)
                } catch (t: Throwable) {
                    Diag.w("ADB", "구버전 서버 종료 실패(무시): ${Diag.chain(t)}")
                }
            }
            else -> {}
        }

        val apk = ctx.packageCodePath
        var lastErr = ""
        for (attempt in 1..2) {
            val cmd = "JX11_TOKEN=${Cfg.adbToken} CLASSPATH=$apk setsid nohup app_process /system/bin " +
                "--nice-name=jx11server com.soby.jx11keymapper.AdbServer ${Cfg.adbPort} " +
                "> $SERVER_LOG 2>&1 < /dev/null & sleep 1; echo started"
            Diag.i("ADB", "서버 기동 시도 #$attempt: adb $host:$port → 서버 포트 ${Cfg.adbPort}")
            try {
                Kadb.create(host, port, 8000, 20000).use { kadb ->
                    val id = kadb.shell("id").allOutput.trim()
                    Diag.i("ADB", "adb 셸 연결 OK · $id")
                    val r = kadb.shell(cmd)
                    Diag.d("ADB", "기동 명령 exit=${r.exitCode} out='${r.allOutput.trim()}'")
                    val deadline = System.currentTimeMillis() + 8000
                    while (System.currentTimeMillis() < deadline) {
                        if (probe() == SocketInput.Result.OK) {
                            Diag.i("ADB", "서버 기동 확인됨 (재부팅 전까지 유지)")
                            return null
                        }
                        Thread.sleep(300)
                    }
                    val tail = try {
                        kadb.shell("tail -n 20 $SERVER_LOG").allOutput.trim()
                    } catch (t: Throwable) {
                        "(로그 읽기 실패: ${t.message})"
                    }
                    lastErr = "서버가 8초 안에 열리지 않음. 서버 로그: ${tail.ifEmpty { "(비어 있음)" }}"
                    Diag.e("ADB", lastErr)
                    if (tail.contains("Address already in use", ignoreCase = true) || tail.contains("EADDRINUSE")) {
                        Cfg.adbPort = 38461 + SecureRandom().nextInt(3000)   // 포트 충돌 → 다른 포트로 한 번 더
                        Cfg.save()
                    } else {
                        return lastErr
                    }
                }
            } catch (t: Throwable) {
                Diag.e("ADB", "서버 기동 중 ADB 오류", t)
                return "ADB 접속/실행 실패: ${Diag.chain(t)}"
            }
        }
        return lastErr
    }
}
