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
    - [ ] daemon mode
    - [ ] cmd

# Usb interface Support
- [x] Fastboot
    - [x] 255/66/3
    - [ ] 255/68/3
- [x] ADB
    - [x] 255/66/1
    - [ ] 6/1/1

# USB 授权
- 默认情况下：Android 系统会自动识别USB设备并弹出授权弹窗
- 非默认情况下：如果 Android系统没有自动识别USB设备，也没有弹出授权弹窗，这种情况下只能点击扫描按钮来主动请求/申请 USB 权限
- 只有授权了 USB 权限，应用程序才能对 USB 设备进行操作，特殊设备可能不需要授权，但我们不会支持这种操作

# ADB 认证
- 默认向adbd发送adbkey私钥进行验证，验证失败之后，发送adbkey.pub公钥来触发计算机授权弹窗，授权之后就可以创建 adb shell 通道，目前不知道为什么私钥验证一直不通过，导致要一直发送公钥来触发计算机授权弹窗

# USB 连接
- 您的数据线与OTG转接口，或者双typec线，在 Android 设备之间进行连接时，应测试数据线连接的稳定性，这样可以有效防止在刷写的过程中会不会一不小心就断开了USB连接从而导致手机变🧱，建议多次触碰数据线以确认USB连接是否稳定