/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
package com.adb.kitty.compose;

interface ICpuBinder {
    // 暴力收割特定核心的 6 项主频指标
    double[] getAllCpuFreqData(int core);
    
    // 收割大件快照：[0]=电池温度, [1]=GPU实时主频(GHz), [2]=GPU真实物理温度
    double[] getHardwareSnapshots();
    
    // 【核心大改动】全盘捞出系统内所有合法的物理热敏探头实时温度
    double[] getRawThermalTemps();
    
    // 【核心大改动】全盘捞出系统内所有热敏探头的底层硬件 Type 别名
    String[] getRawThermalTypes();
}
