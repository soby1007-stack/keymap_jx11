package com.soby.jx11keymapper;

import com.soby.jx11keymapper.IEventCallback;

interface IInputService {
    // Shizuku가 예약한 destroy 트랜잭션 번호
    void destroy() = 16777114;

    void start(String nameFilter, IEventCallback callback) = 1;
    void stop() = 2;
    String listDevices() = 3;
    String ping() = 4;

    // 셸 권한으로 키 입력 주입 (뒤로/홈/방향키 등)
    void sendKey(int keyCode) = 5;
    // 알림창 펼치기
    void expandNotifications() = 6;
    // 장치 독점(원래 터치/스크롤 동작 막기). start() 전에 호출
    void setGrab(boolean on) = 7;
    // 물리 키보드(JX-11 등)가 연결돼 있어도 화면 키보드를 띄울지 (settings secure show_ime_with_hard_keyboard)
    void setImeWithHardKeyboard(boolean show) = 8;
    // 앞 앱 감지(사용 기록 접근)를 셸 권한으로 허용: appops set <pkg> GET_USAGE_STATS allow
    void grantUsageAccess(String pkg) = 9;
    // 휠(세로 이동) 처리 방식: mode 0=키 변환(앱으로 전달) 1=스와이프(배율) 2=마우스 휠 / gainPct 배율(%) / fling 손 뗄 때 관성 / 화면 크기(px)
    void setScroll(int mode, int gainPct, boolean fling, int screenW, int screenH) = 10;
}
