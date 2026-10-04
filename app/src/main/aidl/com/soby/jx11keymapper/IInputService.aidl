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
}
