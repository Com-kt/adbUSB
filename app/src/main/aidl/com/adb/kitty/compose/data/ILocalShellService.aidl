package com.adb.kitty.compose.data;

interface ILocalShellService {
    ParcelFileDescriptor executeCommandStream(String cmd, boolean useRoot);
    void terminateCurrentCommand();
}

