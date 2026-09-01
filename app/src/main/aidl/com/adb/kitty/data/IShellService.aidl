package com.adb.kitty.data;

interface IShellService {
    ParcelFileDescriptor executeCommandStream(String cmd, boolean useRoot);
    void terminateCurrentCommand();
}

