package com.adb.kitty.compose.data;

interface ICpuBinder {
    double[] getAllCpuFreqData(int core);
    
    double[] getHardwareSnapshots();
    
    double[] getRawThermalTemps();
    
    String[] getRawThermalTypes();
}
