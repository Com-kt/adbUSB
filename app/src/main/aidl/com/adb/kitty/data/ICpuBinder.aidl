package com.adb.kitty.data;

interface ICpuBinder {
    double[] getAllCpuFreqData(int core);
    
    double[] getHardwareSnapshots();
    
    double[] getRawThermalTemps();
    
    String[] getRawThermalTypes();
}
