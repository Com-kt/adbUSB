package com.adb.kitty.compose.data;

interface ILocalShellService {
    /**
     * 跨进程执行本地 Shell 命令
     * @param cmd 原始命令字符串
     * @param useRoot 是否强制请求 Root 权限 (由 Activity 路由层智能计算得出)
     */
    String executeCommand(String cmd, boolean useRoot);
}
