# 加入我们的频道
[![Telegram](image/telegram_icon.webp)](https://t.me/+732tNU9GoyhkNGY1)

# Version
- 对于 Release 版本，您可以在 [release](https://github.com/deleteFAILunknown/usbFlash/releases) 中找到它们
- 对于 Beta 版本，您可以在 [beta](https://github.com/deleteFAILunknown/usbFlash/actions) 中找到它们
- 对于 linux 平台工具，您可以在 [platform-tools](linux/platform-tools) 中找到它们，这些平台工具来源于 [android-sdk](https://github.com/HomuHomu833/android-sdk-custom)

# Code
- [x] USB
    - [x] Host (Android 9+)
    - [ ] accessory (Android 12+)
    - [x] UsbManager API (Android 9+)
- [ ] EDL
    - [ ] 9008
    - [ ] 900e
- [x] Fastboot
    - [x] reboot (Android 9+)
    - [x] oem (Android 9+)
    - [x] getvar (Android 9+)
    - [x] erase (Android 9+)
    - [x] flash (Android 9+)
        - [x] Raw Image (Support?)
            - [x] Test Flash OKAY
        - [x] Sparse Image (Support?)
            - [ ] Test Flash OKAY
    - [x] boot (Android 9+)
    - [x] format (Android 9+)
    - [x] set active (Android 9+)
    - [x] usb-Host
        - [x] 255/66/3
        - [ ] 255/68/3
- [x] ADB
    - [x] shell (Android 9+)
    - [x] pull (Android 9+)
    - [x] push (Android 9+)
    - [x] install (Android 9+)
    - [x] uninstall (Android 9+)
    - [x] pair (Android 11+)
    - [x] connect (Android 11+)
    - [x] usb-Host
        - [x] 255/66/1
        - [ ] 6/1/1
- [x] Application
    - [x] aab-Bundle
    - [x] Android TV
    - [x] Android Tablet
    - [x] There may be surprises
    - [x] ipv4 Test
    - [x] ipv6 Test
    - [x] display Test (RootService)
        - [x] CPU
            - [x] SM8475
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