# 加入我们的频道
[![Telegram](image/telegram_icon.webp)](https://t.me/SkisMode)

# USB Host
Android USB Host mode Support
- [x] USB mode
    - [x] Host
    - [ ] accessory
    - [x] UsbManager API
- [x] Fastboot
    - [x] cmd
    - [x] flash
- [x] ADB
    - [x] shell
    - [ ] pull
    - [ ] push
    - [ ] pair

# Usb interface Support
- [x] Fastboot
    - [x] 255/66/3
    - [ ] 255/68/3
- [x] ADB
    - [x] 255/66/1
    - [ ] 6/1/1

> [!WARNING]
> 
> FASTBOOT COMMUNICATION LINK SELF-TEST

# USB 授权
- 默认情况下：Android 系统会自动识别USB设备并弹出授权弹窗
- 非默认情况下：如果 Android系统没有自动识别USB设备，也没有弹出授权弹窗，这种情况下只能点击扫描按钮来主动请求/申请 USB 权限
- 只有授权了 USB 权限，应用程序才能对 USB 设备进行操作，特殊设备可能不需要授权，但我们不会支持这种操作

# ADB 认证
- adbd认证逻辑将来会重构，当前使用的只是临时方案而已

> [!WARNING]
> 
> ADB COMMUNICATION LINK SELF-TEST

# USB 连接
- 您的数据线与OTG转接口，或者双typec线，在 Android 设备之间进行连接时，应测试数据线连接的稳定性，这样可以有效防止在刷写的过程中会不会一不小心就断开了USB连接从而导致手机变🧱，建议多次触碰数据线以确认USB连接是否稳定

## 📢 Maintenance & Contributions

Please note that this is a **personal project** maintained solely by the author.

* **No Pull Requests:** I am **NOT** accepting any code contributions or Pull Requests (PRs) at this time. All PRs will be closed without review.
* **Forks Welcome:** If you wish to add new features, fix bugs, or experiment with the code, please feel free to **Fork** this repository and develop within your own fork.
* **Issues:** If you find a bug, you are welcome to open an **Issue** to report it. I will review and address it at my own discretion.

Thank you for respecting the project's maintenance model.

[Code of Conduct](./CODE_OF_CONDUCT.md)