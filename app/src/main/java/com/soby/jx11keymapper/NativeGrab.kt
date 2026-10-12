package com.soby.jx11keymapper

import java.io.File

/** 장치 독점(EVIOCGRAB)용 네이티브 라이브러리. UserService(shell 프로세스)에서 로드한다. */
object NativeGrab {
    @Volatile
    var loaded = false
        private set

    @Volatile
    var loadError = ""
        private set

    @JvmStatic
    external fun grabOpen(path: String): Int

    @Synchronized
    fun load(): Boolean {
        if (loaded) return true
        val errs = ArrayList<String>()
        try {
            System.loadLibrary("jx11native")
            loaded = true
            return true
        } catch (t: Throwable) {
            errs.add("loadLibrary: ${t.message}")
        }
        // 대체: 프로세스 classpath(base.apk) 옆의 lib 폴더에서 직접 로드
        try {
            val cp = System.getProperty("java.class.path") ?: ""
            for (apk in cp.split(":")) {
                if (!apk.endsWith(".apk")) continue
                val dir = File(apk).parentFile ?: continue
                val cands = listOf(File(dir, "lib/arm64/libjx11native.so"), File(dir, "lib/arm64-v8a/libjx11native.so"))
                for (f in cands) {
                    if (f.exists()) {
                        System.load(f.absolutePath)
                        loaded = true
                        return true
                    }
                }
            }
            errs.add("lib 파일 못 찾음 (classpath=$cp)")
        } catch (t: Throwable) {
            errs.add("System.load: ${t.message}")
        }
        loadError = errs.joinToString(" / ")
        return false
    }
}
