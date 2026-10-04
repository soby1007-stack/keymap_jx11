package com.soby.jx11keymapper

import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.view.InputEvent
import android.view.KeyEvent
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Shizuku UserService — shell(uid 2000) 권한 별도 프로세스에서 실행된다.
 * /dev/input/eventN 을 직접 읽어 EV_KEY / EV_REL 이벤트만 앱으로 전달한다.
 * (이 프로세스에서는 Diag를 쓰지 않고 콜백(onStatus)으로 앱 로그에 남긴다)
 */
class InputService : IInputService.Stub() {

    private class Dev(val name: String, val path: String)

    private val running = AtomicBoolean(false)

    @Volatile
    private var generation = 0
    private val active = ConcurrentHashMap<String, Boolean>()
    private val streams = ConcurrentHashMap<String, FileInputStream>()

    @Volatile
    private var cb: IEventCallback? = null

    @Volatile
    private var filter = ""
    private var scanner: Thread? = null

    override fun destroy() {
        stopInternal()
        exitProcess(0)
    }

    override fun ping(): String = "uid=${Os.getuid()} 64bit=${Process.is64Bit()}"

    override fun listDevices(): String {
        return scanDevices().joinToString("\n") { "${it.name}  (${it.path})" }
    }

    override fun stop() {
        report("I", "SVC", "중지 요청")
        stopInternal()
    }

    // ───────── 키 주입 (셸 권한) ─────────
    // 1순위: IInputManager.injectInputEvent (프로세스 생성 없이 빠름)
    // 2순위: `input keyevent` 명령 (느리지만 확실)

    private var inputManager: Any? = null
    private var injectMethod: java.lang.reflect.Method? = null
    private var fastBroken = false

    private fun initFast() {
        if (injectMethod != null || fastBroken) return
        try {
            val sm = Class.forName("android.os.ServiceManager")
            val binder = sm.getMethod("getService", String::class.java).invoke(null, "input") as IBinder
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            inputManager = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            injectMethod = Class.forName("android.hardware.input.IInputManager")
                .getMethod("injectInputEvent", InputEvent::class.java, Integer.TYPE)
            report("I", "INJECT", "빠른 주입 경로(IInputManager) 사용")
        } catch (e: Throwable) {
            fastBroken = true
            report("W", "INJECT", "빠른 주입 불가 → input 명령으로 대체: ${chain(e)}")
        }
    }

    private fun injectFast(keyCode: Int): Boolean {
        initFast()
        val m = injectMethod ?: return false
        return try {
            val now = SystemClock.uptimeMillis()
            m.invoke(inputManager, KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0), 0)
            m.invoke(inputManager, KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0), 0)
            true
        } catch (e: Throwable) {
            fastBroken = true
            injectMethod = null
            report("W", "INJECT", "빠른 주입 실패 → input 명령으로 대체: ${chain(e)}")
            false
        }
    }

    override fun sendKey(keyCode: Int) {
        val t0 = SystemClock.elapsedRealtime()
        try {
            if (injectFast(keyCode)) {
                report("D", "INJECT", "key $keyCode (fast) ${SystemClock.elapsedRealtime() - t0}ms")
                return
            }
            val p = Runtime.getRuntime().exec(arrayOf("input", "keyevent", keyCode.toString()))
            val rc = p.waitFor()
            report(if (rc == 0) "D" else "W", "INJECT", "key $keyCode (input cmd) rc=$rc ${SystemClock.elapsedRealtime() - t0}ms")
        } catch (e: Throwable) {
            report("E", "INJECT", "key $keyCode 주입 실패: ${chain(e)}")
        }
    }

    override fun expandNotifications() {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("cmd", "statusbar", "expand-notifications"))
            val rc = p.waitFor()
            report(if (rc == 0) "D" else "W", "INJECT", "알림창 펼치기 rc=$rc")
        } catch (e: Throwable) {
            report("E", "INJECT", "알림창 펼치기 실패: ${chain(e)}")
        }
    }

    override fun start(nameFilter: String, callback: IEventCallback) {
        stopInternal()
        filter = nameFilter.trim()
        cb = callback
        running.set(true)
        val gen = generation
        report("I", "SVC", "시작: 필터='$filter' ${ping()}")

        scanner = thread(name = "jx11-scan") {
            var lastSig = "?"
            while (running.get() && gen == generation) {
                try {
                    val all = scanDevices()
                    val matched = all.filter { filter.isEmpty() || it.name.contains(filter, ignoreCase = true) }
                    val sig = matched.joinToString(", ") { "${it.name}(${it.path})" }
                    if (sig != lastSig) {
                        lastSig = sig
                        report("I", "DEVICE", if (matched.isEmpty()) "일치 장치 없음 (필터='$filter', 전체 ${all.size}개)" else "일치: $sig")
                        try {
                            cb?.onDevices(sig)
                        } catch (e: Throwable) {
                            report("W", "DEVICE", "onDevices 전달 실패: ${e.message}")
                        }
                    }
                    for (d in matched) {
                        if (active.putIfAbsent(d.path, true) == null) {
                            thread(name = "jx11-read-${d.path}") { readLoop(d, gen) }
                        }
                    }
                } catch (e: Throwable) {
                    report("E", "DEVICE", "장치 스캔 오류: ${chain(e)}")
                }
                try {
                    Thread.sleep(2000)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    private fun stopInternal() {
        running.set(false)
        generation++
        try {
            scanner?.interrupt()
        } catch (e: Throwable) {
            // 무시
        }
        scanner = null
        for (s in streams.values) {
            try {
                s.close()   // 블록된 read를 깨운다
            } catch (e: Throwable) {
                // 무시
            }
        }
        streams.clear()
        active.clear()
    }

    private fun scanDevices(): List<Dev> {
        val out = ArrayList<Dev>()
        val text = try {
            File("/proc/bus/input/devices").readText()
        } catch (e: Throwable) {
            report("E", "DEVICE", "/proc/bus/input/devices 읽기 실패: ${chain(e)}")
            return out
        }
        for (block in text.split("\n\n")) {
            var name = ""
            var ev: String? = null
            for (line in block.lines()) {
                if (line.startsWith("N: Name=")) {
                    name = line.substringAfter("Name=").trim().trim('"')
                } else if (line.startsWith("H: Handlers=")) {
                    ev = line.substringAfter("Handlers=").trim().split(" ").firstOrNull { it.startsWith("event") }
                }
            }
            if (ev != null) out.add(Dev(name, "/dev/input/$ev"))
        }
        return out
    }

    private fun readLoop(d: Dev, gen: Int) {
        val tsSize = if (Process.is64Bit()) 16 else 8    // struct timeval
        val evSize = tsSize + 8                          // + type(2) code(2) value(4)
        val buf = ByteArray(evSize)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        try {
            val ins = FileInputStream(d.path)
            streams[d.path] = ins
            ins.use {
                report("I", "READ", "열림: ${d.name} (${d.path}) evSize=$evSize")
                while (running.get() && gen == generation) {
                    var off = 0
                    while (off < evSize) {
                        val n = it.read(buf, off, evSize - off)
                        if (n < 0) throw EOFException("입력 장치 EOF")
                        off += n
                    }
                    val type = bb.getShort(tsSize).toInt() and 0xFFFF
                    val code = bb.getShort(tsSize + 2).toInt() and 0xFFFF
                    val value = bb.getInt(tsSize + 4)
                    if (type == 1 || type == 2) {
                        try {
                            cb?.onEvent(type, code, value, d.name)
                        } catch (e: Throwable) {
                            report("W", "READ", "앱으로 전달 실패 → 읽기 중단: ${e.message}")
                            running.set(false)
                            return
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            if (gen == generation) {
                report("W", "READ", "읽기 종료 ${d.path}: ${chain(e)} (장치 재연결 시 자동 재시도)")
            }
        } finally {
            streams.remove(d.path)
            active.remove(d.path)
        }
    }

    private fun report(level: String, stage: String, msg: String) {
        try {
            cb?.onStatus(level, stage, msg)
        } catch (e: Throwable) {
            // 앱이 사라졌으면 무시
        }
    }

    private fun chain(t: Throwable): String {
        val sb = StringBuilder()
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 8) {
            if (depth > 0) sb.append(" ⇐ caused by ")
            sb.append(cur.javaClass.name).append(": ").append(cur.message)
            cur = cur.cause
            depth++
        }
        return sb.toString()
    }
}
