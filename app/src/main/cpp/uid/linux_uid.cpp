#include <jni.h>
#include <unistd.h>
#include <sys/types.h>
#include <fcntl.h>
#include <cstdio>
#include <cstring>
#include <string>

/**
 * 从 /proc/self/status 中解析真实的 Uid/Gid (包含 fsuid/fsgid)
 * 避免触发 Android Seccomp 拦截 setfsuid/setfsgid
 */
static void parseProcStatusIds(uid_t &uid, uid_t &euid, uid_t &suid, uid_t &fsuid,
                               gid_t &gid, gid_t &egid, gid_t &sgid, gid_t &fsgid) {
    FILE *fp = fopen("/proc/self/status", "r");
    if (!fp) return;

    char line[256];
    while (fgets(line, sizeof(line), fp)) {
        if (strncmp(line, "Uid:", 4) == 0) {
            sscanf(line + 4, "%u %u %u %u", &uid, &euid, &suid, &fsuid);
        } else if (strncmp(line, "Gid:", 4) == 0) {
            sscanf(line + 4, "%u %u %u %u", &gid, &egid, &sgid, &fsgid);
        }
    }
    fclose(fp);
}

extern "C" {

JNIEXPORT jintArray JNICALL
Java_com_adb_kitty_data_NativeLibs_getRawIdentityInfo(JNIEnv *env, jclass /* clazz */) {
    uid_t uid = (uid_t)-1, euid = (uid_t)-1, suid = (uid_t)-1, fsuid = (uid_t)-1;
    gid_t gid = (gid_t)-1, egid = (gid_t)-1, sgid = (gid_t)-1, fsgid = (gid_t)-1;

    // 1. 获取 POSIX API 允许的标准 ID
    getresuid(&uid, &euid, &suid);
    getresgid(&gid, &egid, &sgid);

    // 预设兜底值
    fsuid = euid;
    fsgid = egid;

    // 2. 解析 /proc/self/status 安全获取 fsuid / fsgid
    parseProcStatusIds(uid, euid, suid, fsuid, gid, egid, sgid, fsgid);

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
