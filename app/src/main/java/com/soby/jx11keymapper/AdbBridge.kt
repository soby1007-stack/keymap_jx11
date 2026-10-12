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

    /** 이미 떠 있는 서버가 있으면 종료시킨다 (포트/토큰 교체 전에) */
    fun quitServer() {
        val port = Cfg.adbPort
        val token = Cfg.adbToken
        if (port == 0 || token.isEmpty()) return
        val c = SocketInput(port, token)
        val r = c.connect()
        if (r == SocketInput.Result.OK || r == SocketInput.Result.OLD_VERSION) {
            Diag.i("ADB", "기존 서버 종료 요청 ($r)")
            c.destroy()
        } else {
            c.close()
        }
    }

    /**
     * 연결 포트로 adb 에 접속해 shell 권한 서버를 띄운다. 성공하면 null, 실패하면 원인 문자열.
     * 백그라운드 스레드에서 호출.
     */
    fun launchServer(ctx: Context, host: String, port: Int): String? {
        initKey(ctx)
        try {
            quitServer()
        } catch (t: Throwable) {
            Diag.w("ADB", "기존 서버 정리 실패(무시): ${Diag.chain(t)}")
        }
        Cfg.adbPort = 20000 + SecureRandom().nextInt(30000)
        Cfg.adbToken = randomToken()
        Cfg.save()

        val apk = ctx.packageCodePath
        val cmd = "(CLASSPATH=$apk nohup app_process /system/bin --nice-name=jx11server " +
            "com.soby.jx11keymapper.AdbServer ${Cfg.adbPort} ${Cfg.adbToken} > $SERVER_LOG 2>&1 < /dev/null &)"
        Diag.i("ADB", "서버 기동: adb $host:$port → port ${Cfg.adbPort}")
        var logTail = ""
        try {
            Kadb.create(host, port, 8000, 20000).use { kadb ->
                val r = kadb.shell(cmd)
                Diag.d("ADB", "기동 명령 exit=${r.exitCode} out='${r.allOutput.trim()}'")
                // 서버가 소켓을 열 때까지 대기
                val deadline = System.currentTimeMillis() + 8000
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(400)
                    val c = SocketInput(Cfg.adbPort, Cfg.adbToken)
                    val res = c.connect()
                    c.close()
                    if (res == SocketInput.Result.OK) {
                        Diag.i("ADB", "서버 기동 확인됨")
                        return null
                    }
                }
                logTail = try {
                    kadb.shell("tail -n 20 $SERVER_LOG").allOutput.trim()
                } catch (t: Throwable) {
                    "(로그 읽기 실패: ${t.message})"
                }
            }
        } catch (t: Throwable) {
            val msg = Diag.chain(t)
            Diag.e("ADB", "서버 기동 중 ADB 오류", t)
            return "ADB 접속/실행 실패: $msg"
        }
        return "서버가 8초 안에 열리지 않음. 서버 로그: ${logTail.ifEmpty { "(비어 있음)" }}"
    }
}
