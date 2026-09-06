package com.adb.kitty.data.ipc;

interface Ipc {
    boolean isRunning();
    String getRunningTime();
    void exit() = 1;
}