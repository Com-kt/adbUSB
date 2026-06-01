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
    /**
     * [0] -> cpuinfo_cur_freq
     * [1] -> cpuinfo_max_freq
     * [2] -> cpuinfo_min_freq
     * [3] -> scaling_max_freq
     * [4] -> scaling_min_freq
     * [5] -> scaling_cur_freq
     */
    double[] getAllCpuFreqData(int coreIndex);
}
