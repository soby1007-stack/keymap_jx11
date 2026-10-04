package com.soby.jx11keymapper

/** 리눅스 input 이벤트 코드 → 사람이 읽는 이름 */
object EventNames {
    const val EV_KEY = 1
    const val EV_REL = 2
    const val EV_GESTURE = 100   // 가상 이벤트: 터치 좌표에서 만든 스와이프/탭

    private val gesture = mapOf(1 to "스와이프 ↑", 2 to "스와이프 ↓", 3 to "스와이프 ←", 4 to "스와이프 →", 5 to "탭")

    private val rel = mapOf(
        0 to "REL_X", 1 to "REL_Y", 2 to "REL_Z",
        6 to "REL_HWHEEL", 7 to "REL_DIAL", 8 to "REL_WHEEL", 9 to "REL_MISC",
        11 to "REL_WHEEL_HI_RES", 12 to "REL_HWHEEL_HI_RES"
    )

    private val key = mapOf(
        1 to "KEY_ESC", 28 to "KEY_ENTER", 57 to "KEY_SPACE",
        102 to "KEY_HOME", 103 to "KEY_UP", 104 to "KEY_PAGEUP", 105 to "KEY_LEFT",
        106 to "KEY_RIGHT", 107 to "KEY_END", 108 to "KEY_DOWN", 109 to "KEY_PAGEDOWN",
        113 to "KEY_MUTE", 114 to "KEY_VOLUMEDOWN", 115 to "KEY_VOLUMEUP",
        119 to "KEY_PAUSE", 128 to "KEY_STOP", 139 to "KEY_MENU", 158 to "KEY_BACK",
        163 to "KEY_NEXTSONG", 164 to "KEY_PLAYPAUSE", 165 to "KEY_PREVIOUSSONG", 207 to "KEY_PLAY",
        272 to "BTN_LEFT", 273 to "BTN_RIGHT", 274 to "BTN_MIDDLE", 330 to "BTN_TOUCH"
    )

    fun typeName(type: Int): String = when (type) {
        EV_KEY -> "EV_KEY"
        EV_REL -> "EV_REL"
        EV_GESTURE -> "GESTURE"
        else -> "EV_$type"
    }

    fun codeName(type: Int, code: Int): String = when (type) {
        EV_REL -> rel[code] ?: "REL_$code"
        EV_KEY -> key[code] ?: "KEY_$code"
        EV_GESTURE -> gesture[code] ?: "제스처$code"
        else -> "CODE_$code"
    }

    fun label(type: Int, code: Int, sign: Int): String {
        val base = codeName(type, code)
        return when {
            type == EV_REL && sign > 0 -> "$base(+)"
            type == EV_REL && sign < 0 -> "$base(-)"
            else -> base
        }
    }
}
