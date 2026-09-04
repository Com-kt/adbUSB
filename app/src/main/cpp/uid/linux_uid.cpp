#include <jni.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/fsuid.h>
#include <fcntl.h>
#include <string>

extern "C" {

/**
 * 获取 14 个基础整数标识并填充到 jintArray 中返回
 * 数组顺序：[uid, euid, suid, fsuid, gid, egid, sgid, fsgid, pid, ppid, tid, pgid, sid, aid]
 */
JNIEXPORT jintArray JNICALL
Java_com_adb_kitty_data_NativeLibs_getRawIdentityInfo(JNIEnv *env, jclass /* clazz */) {
    uid_t uid = (uid_t)-1, euid = (uid_t)-1, suid = (uid_t)-1, fsuid = (uid_t)-1;
    gid_t gid = (gid_t)-1, egid = (gid_t)-1, sgid = (gid_t)-1, fsgid = (gid_t)-1;

    // 1. 获取 UID / EUID / SUID / FSUID
    getresuid(&uid, &euid, &suid);
    fsuid = setfsuid(-1);

    // 2. 获取 GID / EGID / SGID / FSGID
    getresgid(&gid, &egid, &sgid);
    fsgid = setfsgid(-1);

    // 3. 获取进程与线程标识
    pid_t pid = getpid();
    pid_t ppid = getppid();
    pid_t tid = gettid();
    pid_t pgid = getpgid(0);
    pid_t sid = getsid(0);

    // 计算 AID
    uid_t aid = (uid != (uid_t)-1) ? (uid % 100000) : (uid_t)-1;

    jint buf[14] = {
        (jint)uid,  (jint)euid,  (jint)suid,  (jint)fsuid,
        (jint)gid,  (jint)egid,  (jint)sgid,  (jint)fsgid,
        (jint)pid,  (jint)ppid,  (jint)tid,   (jint)pgid,
        (jint)sid,  (jint)aid
    };

    jintArray result = env->NewIntArray(14);
    if (result != nullptr) {
        env->SetIntArrayRegion(result, 0, 14, buf);
    }
    return result;
}

/**
 * 获取 SELinux Context 字符串
 */
JNIEXPORT jstring JNICALL
Java_com_adb_kitty_data_NativeLibs_getSelinuxContext(JNIEnv *env, jclass /* clazz */) {
    std::string selinuxContext = "unknown";
    int fd = open("/proc/self/attr/current", O_RDONLY);
    if (fd >= 0) {
        char buf[256] = {0};
        ssize_t bytesRead = read(fd, buf, sizeof(buf) - 1);
        if (bytesRead > 0) {
            if (buf[bytesRead - 1] == '\n') buf[bytesRead - 1] = '\0';
            selinuxContext = buf;
        }
        close(fd);
    }
    return env->NewStringUTF(selinuxContext.c_str());
}

} // extern "C"
