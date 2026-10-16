package com.soby.jx11keymapper

import android.os.IBinder
import android.os.ParcelFileDescriptor
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
 * /dev/input/eventN 을 직접 읽어 EV_KEY / EV_REL 과 터치 좌표(→제스처)를 앱으로 전달한다.
 * (이 프로세스에서는 Diag를 쓰지 않고 콜백(onStatus)으로 앱 로그에 남긴다)
 */
class InputService : IInputService.Stub() {

    private class Dev(val name: String, val path: String) {
        @Volatile var rangeX = 0
        @Volatile var rangeY = 0
    }

    /**
     * JX-11 은 휠이 아니라 터치패드(디지타이저) 방식이라 REL_WHEEL 이 아닌
     * BTN_TOUCH + ABS 좌표로 입력이 온다. 손가락(휠)이 움직이는 동안 프레임(SYN)마다 좌표 변화량을
     * 스와이프 조각(코드 1~4, value = 이동량 ‰)으로 바로 앱에 보낸다 → 앱이 "N‰ 움직일 때마다 1회"로 환산.
     * 손가락이 떨어질 때 나오는 튀는 좌표(0 이나 범위 밖, 한 프레임에 25% 넘게 점프)는 버린다.
     * 거의 안 움직이고 짧게 떼면 탭(5).
     */
    private inner class Gesture(val d: Dev) {
        var down = false
        var x = 0
        var y = 0
        var gotX = false
        var gotY = false
        var axOk = false
        var ayOk = false
        var lx = 0
        var ly = 0
        var bad = 0
        var t0 = 0L
        var travel = 0f
        var raw = 0

        private fun begin() {
            if (down) return
            down = true
            gotX = false
            gotY = false
            axOk = false
            ayOk = false
            bad = 0
            travel = 0f
            t0 = SystemClock.elapsedRealtime()
            report("D", "GESTURE", "${d.name} 터치 시작 (range ${d.rangeX}x${d.rangeY})")
        }

        /** 프레임 하나: 직전에 인정한 좌표와 비교해 이동량을 내보낸다 */
        private fun frame(emit: (Int, Int) -> Unit) {
            if (!down) return
            val rx = (if (d.rangeX > 0) d.rangeX else 4096).toFloat()
            val ry = (if (d.rangeY > 0) d.rangeY else 4096).toFloat()
            var dx = 0
            var dy = 0
            if (gotX) {
                if (axOk) dx = x - lx else { lx = x; axOk = true }
            }
            if (gotY) {
                if (ayOk) dy = y - ly else { ly = y; ayOk = true }
            }
            if (dx == 0 && dy == 0) return
            val fx = dx / rx
            val fy = dy / ry
            if (Math.abs(fx) > 0.25f || Math.abs(fy) > 0.25f) {
                bad++
                report("D", "GESTURE", "${d.name} 튀는 좌표 무시 dx=$dx dy=$dy (연속 $bad)")
                if (bad >= 3) {   // 계속 튄다 = 진짜 큰 이동 → 현재 좌표로 기준을 다시 잡는다
                    lx = x
                    ly = y
                    bad = 0
                }
                return
            }
            bad = 0
            if (gotX) lx = x
            if (gotY) ly = y
            val ax = Math.abs(fx)
            val ay = Math.abs(fy)
            travel += (ax + ay) * 1000f
            if (ay >= ax) {
                emit(if (fy < 0) 1 else 2, Math.max(1, Math.round(ay * 1000f)))
            } else {
                emit(if (fx < 0) 3 else 4, Math.max(1, Math.round(ax * 1000f)))
            }
        }

        /** 손가락이 떨어짐: 거의 안 움직였고 짧으면 탭 */
        private fun end(emit: (Int, Int) -> Unit) {
            if (!down) return
            down = false
            val dt = SystemClock.elapsedRealtime() - t0
            if (travel < 5f) {
                report("D", "GESTURE", "${d.name} 탭 (${dt}ms, 이동 ${travel.toInt()}‰)")
                if (dt < 600) emit(5, 1)
            } else {
                report("D", "GESTURE", "${d.name} 터치 끝 (${dt}ms, 인정한 이동 합계 ${travel.toInt()}‰)")
            }
        }

        fun feed(type: Int, code: Int, value: Int, emit: (Int, Int) -> Unit) {
            if (type != 0 && raw < 80) {
                raw++
                report("D", "RAW", "${d.name} type=$type code=$code value=$value")
            }
            when (type) {
                1 -> if (code == 330) {
                    if (value == 1) begin() else if (value == 0) end(emit)
                }
                3 -> when (code) {
                    0, 53 -> { x = value; gotX = true }
                    1, 54 -> { y = value; gotY = true }
                    57 -> if (value >= 0) begin() else end(emit)
                }
                0 -> if (code == 0) frame(emit)
            }
        }
    }

    /** 읽은 이벤트 1개 처리: KEY/REL 은 그대로, 터치 좌표는 제스처로 변환해서 전달 */
    private fun dispatch(d: Dev, g: Gesture, type: Int, code: Int, value: Int) {
        if (type == 1 || type == 2) cb?.onEvent(type, code, value, d.name)
        g.feed(type, code, value) { c, v ->
            cb?.onEvent(100, c, v, d.name)
        }
    }

    private val running = AtomicBoolean(false)

    @Volatile
    private var generation = 0
    private val active = ConcurrentHashMap<String, Boolean>()
    private val streams = ConcurrentHashMap<String, java.io.Closeable>()

    @Volatile
    private var grab = false

    override fun setGrab(on: Boolean) {
        grab = on
        report("I", "SVC", "장치 독점(GRAB) 요청: $on")
    }

    override fun setImeWithHardKeyboard(show: Boolean) {
        try {
            val v = if (show) "1" else "0"
            val p = Runtime.getRuntime().exec(arrayOf("settings", "put", "secure", "show_ime_with_hard_keyboard", v))
            val rc = p.waitFor()
            val err = p.errorStream.bufferedReader().readText().trim()
            report(if (rc == 0) "I" else "W", "SVC", "화면 키보드 표시(물리 키보드 연결 중) = $v rc=$rc ${err.take(200)}")
        } catch (e: Throwable) {
            report("E", "SVC", "화면 키보드 설정 실패: ${chain(e)}")
        }
    }

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
        return scanDevices(true).joinToString("\n") { "${it.name}  (${it.path})" }
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
            var lastAllSig = "?"
            while (running.get() && gen == generation) {
                try {
                    val all = scanDevices()
                    val allSig = all.joinToString(", ") { "${it.name}(${it.path})" }
                    if (allSig != lastAllSig) {
                        lastAllSig = allSig
                        report("I", "DEVICE", "전체 입력장치 ${all.size}개: $allSig")
                    }
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
        for (p in procs.values) {
            try {
                p.destroy()
            } catch (e: Throwable) {
                // 무시
            }
        }
        procs.clear()
        active.clear()
    }

    // ───────── 장치 탐색 ─────────
    // Android 15 등에서는 shell 이 /proc/bus/input/devices 를 못 읽는다(EACCES).
    // 그래서 `getevent -lp` (이름 조회) 결과를 파싱하고, /dev/input 노드 목록이
    // 바뀐 때만 다시 조회한다.

    private val addRe = Regex("add device \\d+: (\\S+)")
    private val nameRe = Regex("^\\s*name:\\s+\"(.*)\"")
    private val absRe = Regex("(ABS_X|ABS_Y|ABS_MT_POSITION_X|ABS_MT_POSITION_Y)\\s*:.*?min\\s+(-?\\d+),\\s*max\\s+(-?\\d+)")
    private var lastNodes: Set<String> = emptySet()
    private var nameCache: List<Dev> = emptyList()
    private var lastQueryErr = ""

    @Synchronized
    private fun scanDevices(force: Boolean = false): List<Dev> {
        val nodes: Set<String>? = File("/dev/input").list()?.filter { it.startsWith("event") }?.toSet()
        if (!force && nodes != null && nodes == lastNodes && nameCache.isNotEmpty()) return nameCache
        val devs = queryDevices()
        lastNodes = nodes ?: emptySet()
        nameCache = devs
        return devs
    }

    private fun queryDevices(): List<Dev> {
        val out = ArrayList<Dev>()
        val text: String
        try {
            val p = ProcessBuilder("/system/bin/getevent", "-lp").redirectErrorStream(true).start()
            text = p.inputStream.bufferedReader().readText()
            val rc = p.waitFor()
            report("D", "DEVICE", "getevent -lp rc=$rc 출력 ${text.length}자")
            lastQueryErr = ""
        } catch (e: Throwable) {
            val msg = chain(e)
            if (msg != lastQueryErr) {
                lastQueryErr = msg
                report("E", "DEVICE", "getevent -lp 실행 실패: $msg")
            }
            return out
        }
        var path: String? = null
        var cur: Dev? = null
        for (line in text.lines()) {
            val a = addRe.find(line)
            if (a != null) {
                path = a.groupValues[1]
                cur = null
                continue
            }
            val n = nameRe.find(line)
            if (n != null && path != null) {
                cur = Dev(n.groupValues[1], path)
                out.add(cur)
                path = null
                continue
            }
            val r = absRe.find(line)
            if (r != null && cur != null) {
                val span = r.groupValues[3].toInt() - r.groupValues[2].toInt()
                if (r.groupValues[1].endsWith("X")) cur.rangeX = span else cur.rangeY = span
            }
        }
        for (d in out) {
            if (d.rangeX > 0 || d.rangeY > 0) report("D", "DEVICE", "${d.name}(${d.path}) 좌표범위 ${d.rangeX}x${d.rangeY}")
        }
        if (out.isEmpty()) {
            val msg = "getevent -lp 결과에서 장치를 하나도 못 찾음 (출력 앞부분: ${text.take(200).replace("\n", " ")})"
            if (msg != lastQueryErr) {
                lastQueryErr = msg
                report("E", "DEVICE", msg)
            }
        }
        return out
    }

    // ───────── 대체 읽기: 직접 open 이 막히면 `getevent <장치>` 숫자 출력 파싱 ─────────

    private val procs = ConcurrentHashMap<String, java.lang.Process>()
    private val numRe = Regex("^(?:/dev/\\S+:\\s*)?([0-9a-fA-F]{4})\\s+([0-9a-fA-F]{4})\\s+([0-9a-fA-F]{8})\\s*$")

    private fun readViaGetevent(d: Dev, gen: Int) {
        report("I", "READ", "getevent 대체 경로 시작: ${d.name} (${d.path})")
        val p = ProcessBuilder("/system/bin/getevent", d.path).redirectErrorStream(true).start()
        procs[d.path] = p
        val g = Gesture(d)
        try {
            p.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!running.get() || gen != generation) break
                    val m = numRe.find(line.trim())
                    if (m == null) {
                        report("D", "READ", "무시: $line")
                        continue
                    }
                    val type = m.groupValues[1].toInt(16)
                    val code = m.groupValues[2].toInt(16)
                    val value = m.groupValues[3].toLong(16).toInt()
                    dispatch(d, g, type, code, value)
                }
            }
        } finally {
            procs.remove(d.path)
            p.destroy()
        }
        report("W", "READ", "getevent 대체 경로 종료: ${d.path}")
    }

    private fun readLoop(d: Dev, gen: Int) {
        val tsSize = if (Process.is64Bit()) 16 else 8    // struct timeval
        val evSize = tsSize + 8                          // + type(2) code(2) value(4)
        val buf = ByteArray(evSize)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val g = Gesture(d)
        try {
            var closer: java.io.Closeable? = null
            var ins: FileInputStream? = null
            var grabbed = false
            if (grab) {
                if (!NativeGrab.load()) {
                    report("W", "GRAB", "네이티브 라이브러리 로드 실패 → 독점 없이 읽기: ${NativeGrab.loadError}")
                } else {
                    val r = NativeGrab.grabOpen(d.path)
                    if (r >= 0) {
                        val pfd = ParcelFileDescriptor.adoptFd(r)
                        closer = pfd
                        ins = FileInputStream(pfd.fileDescriptor)
                        grabbed = true
                        report("I", "GRAB", "독점 성공: ${d.name} (${d.path}) → 이 장치의 원래 동작은 차단됨")
                    } else {
                        val why = if (r <= -1000) "EVIOCGRAB errno=${-r - 1000}" else "open errno=${-r}"
                        report("W", "GRAB", "독점 실패(${d.path}): $why → 독점 없이 읽기")
                    }
                }
            }
            if (ins == null) {
                ins = try {
                    FileInputStream(d.path)
                } catch (e: java.io.FileNotFoundException) {
                    report("W", "READ", "직접 열기 실패(${d.path}): ${e.message} → getevent 명령으로 대체")
                    readViaGetevent(d, gen)
                    return
                }
                closer = ins
            }
            streams[d.path] = closer!!
            try {
                report("I", "READ", "열림: ${d.name} (${d.path}) evSize=$evSize grab=$grabbed")
                while (running.get() && gen == generation) {
                    var off = 0
                    while (off < evSize) {
                        val n = ins!!.read(buf, off, evSize - off)
                        if (n < 0) throw EOFException("입력 장치 EOF")
                        off += n
                    }
                    val type = bb.getShort(tsSize).toInt() and 0xFFFF
                    val code = bb.getShort(tsSize + 2).toInt() and 0xFFFF
                    val value = bb.getInt(tsSize + 4)
                    try {
                        dispatch(d, g, type, code, value)
                    } catch (e: Throwable) {
                        report("W", "READ", "앱으로 전달 실패 → 읽기 중단: ${e.message}")
                        running.set(false)
                        return
                    }
                }
            } finally {
                try {
                    closer?.close()
                } catch (e: Throwable) {
                    // 무시
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
