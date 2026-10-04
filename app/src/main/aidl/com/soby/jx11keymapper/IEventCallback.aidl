package com.soby.jx11keymapper;

oneway interface IEventCallback {
    void onEvent(int type, int code, int value, String device);
    void onStatus(String level, String stage, String msg);
    void onDevices(String summary);
}
