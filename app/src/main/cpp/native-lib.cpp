#include <system_error>
#include <jni.h>
#include <iostream>
#include <string>
#include <vector>
#include <algorithm>
#include <cmath>
#include <list>
#include <map>
#include <set>
#include <unordered_map> 
#include <unordered_set>
#include <fstream>
#include <memory>
#include <thread>
#include <chrono>
#include <exception>
#include <android/log.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/wait.h>

#include <fcntl.h>
#include <cstring>
#include <unistd.h>
#include <cstdlib>
#include <cstdio>

#include <cstdint>
#include <cstring>
#include <iomanip>
#include <sstream>

const char* USER_KITTY_CONSTANT = "FAILunknown";

extern "C" JNIEXPORT jstring JNICALL
Java_com_adb_kitty_compose_data_NativeLibs_UserString(JNIEnv *env, jobject) {
    return env->NewStringUTF(USER_KITTY_CONSTANT);
}
