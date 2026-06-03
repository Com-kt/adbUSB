/*
 * Copyright (c) 2026-2030 小猫猫. All rights reserved.
 * 
 * LICENSE NOTE:
 * Any redistribution must retain this copyright notice and license disclaimer.
 *
 * by: 小猫猫
 */
package com.adb.kitty;

interface ICpuBinder {
    // 一次性收割特定核心的 6 项频率指标数据
    double[] getAllCpuFreqData(int core);
    
    // 一次性收割全大件多维硬件快照 [0]=CPU综合, [1]=电池温度, [2]=GPU温度, [3]=GPU实时主频(GHz)
    double[] getSystemTemperatures();
    
    // 一次性收割全系自适应拓扑出来的 8 个物理核心实时高精温度
    double[] getAllCpuCoreTemps();
}
