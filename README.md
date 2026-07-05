# 加入我们的频道
[![Telegram](image/telegram_icon.webp)](https://t.me/+732tNU9GoyhkNGY1)

# Version
- 对于 Release 版本，您可以在 [release](https://github.com/deleteFAILunknown/usbFlash/releases) 中找到它们
- 对于 Beta 版本，您可以在 [beta](https://github.com/deleteFAILunknown/usbFlash/actions) 中找到它们
- 对于 linux 平台工具，您可以在 [platform-tools](linux/platform-tools) 中找到它们，这些平台工具来源于 [android-sdk](https://github.com/HomuHomu833/android-sdk-custom)

# Code
- [x] USB
    - [x] Host (Android 10+)
    - [ ] accessory (Android 12+)
    - [x] UsbManager API (Android 10+)
- [ ] EDL
    - [ ] 9008
    - [ ] 900e
- [x] Fastboot
    - [x] reboot (Android 10+)
    - [x] oem (Android 10+)
    - [x] getvar (Android 10+)
    - [x] erase (Android 10+)
    - [x] flash (Android 10+)
        - [x] Raw Image (Support?)
            - [x] Test Flash OKAY
        - [x] Sparse Image (Support?)
            - [ ] Test Flash OKAY
    - [x] boot (Android 10+)
    - [x] format (Android 10+)
    - [x] set active (Android 10+)
    - [x] usb-Host
        - [x] 255/66/3
- [x] ADB
    - [x] shell (Android 10+)
    - [x] pull (Android 10+)
    - [x] push (Android 10+)
    - [x] install (Android 10+)
    - [x] uninstall (Android 10+)
    - [x] pair (Android 11+)
    - [x] connect (Android 11+)
    - [x] usb-Host
        - [x] 255/66/1
- [x] Application
    - [x] aab-Bundle
    - [x] Android TV
    - [x] Android Tablet
    - [x] Wi-Fi P2P
    - [x] There may be surprises
    - [x] ipv4 Test
    - [x] ipv6 Test
    - [x] display Test (RootService)
        - [x] CPU
            - [x] SM8650
    - [x] MTFilesProvider
    - [x] Kernel frequency raising (Android 12+)
    - [x] Library
        - [x] arm64-v8a
        - [x] armeabi-v7a
        - [x] x86
        - [x] x86_64

> [!WARNING]
> 
> MTK UNSUPPORTED

# 安装前必须要注意的事项
- Android 清单文件
```xml
<!-- 允许备份数据 -->
android:allowBackup="true"

<!-- 允许自由调整窗口 -->
android:resizeableActivity="true"

<!-- 允许硬件加速 -->
android:hardwareAccelerated="true"

<!-- 安装位置由系统自动选择 -->
android:installLocation="auto"

<!-- 应用被声明为持久性 -->
android:persistent="true"

<!-- 允许申请大堆内存 -->
android:largeHeap="true"

<!-- Android SDK 平台 -->
<uses-sdk
    <!-- minSdk = 最低支持的系统 -->
    android:minSdkVersion="29"
    <!-- targetSdk = 目标支持的系统 -->
    android:targetSdkVersion="37" />
```

# Wi-Fi P2P
- 本地身份：GO[群主]，GC[组员]
- 尝试强制协商Wi-Fi P2P本地身份，只需要在连接时传入 --GO 或者 --GC
```
情况A：
    设备A：作为接收端，并且身份为GO[群主]
     └─初始化 >> p2p-search
     └─设备A点击同意连接
     └─接收数据 >> p2p-receive
     └─等待设备B执行完发送数据

    设备B：作为发送端，并且身份为GC[组员]
     └─初始化 >> p2p-search
     └─搜索设备 >> p2p-list
     └─连接设备 >> p2p-connect <Wi-Fi MAC地址>
     └─查询状态 >> p2p-status
     └─发送数据 >> p2p-send <flash目录下的文件或文件夹>
     
───────────────────────────────
情况B：
    设备A：作为接收端，并且身份为GC[组员]
     └─初始化 >> p2p-search
     └─设备A点击同意连接
     └─等待设备B执行完发送数据，再执行接收数据
     └─接收数据 >> p2p-receive

    设备B：作为发送端，并且身份为GO[群主]
     └─初始化 >> p2p-search
     └─搜索设备 >> p2p-list
     └─连接设备 >> p2p-connect <Wi-Fi MAC地址>
     └─查询状态 >> p2p-status
     └─发送数据 >> p2p-send <flash目录下的文件或文件夹>
     
───────────────────────────────
传入自定义端口，不使用默认端口52020进行传输
p2p-send
    语法：
    p2p-send --port=端口 <flash目录下的文件或文件夹>
    
p2p-receive
    语法：
    p2p-receive --port=端口 <flash目录下的文件或文件夹>

───────────────────────────────
自定义群组，跨平台兼容[Windows/Linux/Mac]
p2p-create-group
    语法：
    p2p-create-group --ssid=<名字> --pass=<密码>
    
连接方式：在Wi-Fi列表里找到它，并输入密码，最后连接它

连接之后能干什么？
  很简单，你可以通过 192.168.49.1:端口 来进行数据传输
  也可以通过 192.168.49.1:端口 来进行无线调试
  究竟能不能让电脑通过app创建的p2p通道进行无线调试和Scrcpy就看你们自己的了
  
───────────────────────────────
Socks5 代理，p2p直连通道
    p2p-start-proxy >> 开启
    p2p-stop-proxy >> 停止
    
支持 CONNECT，UDP

───────────────────────────────
一键解散p2p群组
p2p-reset
```

# 其他 ADB 工具
- 如果您不想使用此GitHub仓库实现的ADB工具，那么您也可以使用其他GitHub仓库实现的ADB工具
- 以下列出一些包含了 ADB 工具以及实现：
- [aShellYou](https://github.com/DP-Hridayan/aShellYou)
- [Shizuku](https://github.com/RikkaApps/Shizuku)
- [AppManager](https://github.com/MuntashirAkon/AppManager)

> [!WARNING]
> 
> 无论您使用什么 ADB 工具都需要具备一些 ADB 基础知识以及经验

# 原著者声明
- /*
- * Copyright (c) 2026-2030 evil spirits. All rights reserved.
- * 
- * LICENSE NOTE:
- * Any redistribution must retain this copyright notice and license disclaimer.
- *
- * by: evil spirits
- */

## Acknowledgements

- [Kadb](https://github.com/flyfishxu/Kadb)
- [android-sdk](https://github.com/HomuHomu833/android-sdk-custom)
- [libsu](https://github.com/topjohnwu/libsu)
- [bkerler-edl](https://github.com/bkerler/edl)
- [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass)