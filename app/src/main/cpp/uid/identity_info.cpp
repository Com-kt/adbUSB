#include <iostream>
#include <fstream>
#include <string>
#include <unistd.h>
#include <sys/types.h>
#include <sys/fsuid.h>
#include <fcntl.h>

int main(int argc, char* argv[]) {
    uid_t uid = -1, euid = -1, suid = -1, fsuid = -1;
    gid_t gid = -1, egid = -1, sgid = -1, fsgid = -1;

    // 1. 获取 UIDs (setfsuid 在独立可执行文件中安全无拦截)
    getresuid(&uid, &euid, &suid);
    fsuid = setfsuid(-1);

    // 2. 获取 GIDs
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

    // 4. 获取 SELinux Context
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

    // 5. 格式化控制台输出
    std::cout << "========== Identity Info ==========" << std::endl;
    std::cout << "UID   : " << uid   << " | EUID : " << euid  << " | SUID : " << suid  << " | FSUID : " << fsuid << std::endl;
    std::cout << "GID   : " << gid   << " | EGID : " << egid  << " | SGID : " << sgid  << " | FSGID : " << fsgid << std::endl;
    std::cout << "PID   : " << pid   << " | PPID : " << ppid  << " | TID  : " << tid   << " | PGID  : " << pgid  << " | SID : " << sid << std::endl;
    std::cout << "AID   : " << aid   << std::endl;
    std::cout << "SELinux: " << selinuxContext << std::endl;
    std::cout << "===================================" << std::endl;

    return 0;
}
