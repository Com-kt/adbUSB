# 加入我们的频道
[![Telegram](image/telegram_icon.webp)](https://t.me/SkisMode)

# Work
> [!WARNING]
> 
> THIS PROJECT IS NOT MAINTAINED ANYMORE.

# Code
- [x] USB mode
    - [x] Host (Android 9+)
    - [ ] accessory (Android 12+)
    - [x] UsbManager API (Android 9+)
- [x] Fastboot
    - [x] cmd (Android 9+)
        - [x] oem
        - [x] getvar
        - [x] erase
    - [x] flash (Android 9+)
        - [x] flash_all.sh
        - [x] partition-table
    - [x] interface
        - [x] 255/66/3
        - [ ] 255/68/3
- [x] ADB
    - [x] shell (Android 9+)
    - [x] pull (Android 9+)
    - [x] push (Android 9+)
    - [x] pair (Android 11+)
    - [x] connect (Android 11+)
    - [x] interface
        - [x] 255/66/1
        - [ ] 6/1/1
- [x] Application
    - [x] ipv4 Test
    - [x] ipv6 Test
    - [x] display Test (RootService)
    - [x] MTFilesProvider
    - [x] Kernel frequency raising (Android 12+)
    - [x] Library
        - [x] arm64-v8a
        - [x] armeabi-v7a
        - [x] x86
        - [x] x86_64
        - [x] riscv64
- [x] CPU
    - [x] SM8475：
        - [x] 第一代骁龙 8+ (Snapdragon 8+ Gen 1)
        - [x] 1+3+4
    - [x] SM8550：
        - [x] 第二代骁龙 8 (Snapdragon 8 Gen 2)
        - [x] 1+(2+2)+3
    - [x] SM8650：
        - [x] 第三代骁龙 8 (Snapdragon 8 Gen 3)
        - [x] 1+5+2
    - [x] SM8750：
        - [x] 骁龙 8 至尊版 (Snapdragon 8 Elite / 8e)
        - [x] 2+6
    - [x] SM8850：
        - [x] 第五代骁龙 8 至尊版 (Snapdragon 8 Elite Gen 5 / 8e5)
        - [x] 2+6

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
- * Copyright (c) 2026-2030 小猫猫. All rights reserved.
- * 
- * LICENSE NOTE:
- * Any redistribution must retain this copyright notice and license disclaimer.
- *
- * by: 小猫猫
- */

## Acknowledgements

- [Kadb](https://github.com/flyfishxu/Kadb)
- [android-sdk](https://github.com/HomuHomu833/android-sdk-custom)
- [RootService](https://github.com/topjohnwu/libsu)
