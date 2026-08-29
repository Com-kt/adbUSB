package com.adb.kitty.compose.data;

interface IShellService {
    ParcelFileDescriptor executeCommandStream(String cmd, boolean useRoot);
    void terminateCurrentCommand();
}

