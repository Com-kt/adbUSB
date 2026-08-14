# Version
- Release Version = [release](https://github.com/deleteFAILunknown/usbFlash/releases)
- Beta Version = [beta](https://github.com/deleteFAILunknown/usbFlash/actions)
- linux Tools = [android-sdk](https://github.com/HomuHomu833/android-sdk-custom)

# apk support range
- Android 17 - Android 7.0
- Android TV、Android

# Signature scheme sample script
- Now there are not only sample scripts in the project, but also built APKs, which use signature schemes v2, v3, v3.1, and v3.2 respectively.
- Hope this sample script can help you

# Verify signature scheme
- Verify Release APK
```shell
$ ./apksigner verify -v --verbose app-release-sign.apk
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v3.1 scheme (APK Signature Scheme v3.1): true
Verified using v3.2 scheme (APK Signature Scheme v3.2): true
Verified using v4 scheme (APK Signature Scheme v4): false
Verified for SourceStamp: false
Number of signers: 1
```
- Verify Debug APK
```shell
$ ./apksigner verify -v --verbose app-debug-sign.apk
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v3.1 scheme (APK Signature Scheme v3.1): true
Verified using v3.2 scheme (APK Signature Scheme v3.2): true
Verified using v4 scheme (APK Signature Scheme v4): false
Verified for SourceStamp: false
Number of signers: 1
```

## Commercial license
- commercialization allowed
- Allow transactional
- Allow templating

## Acknowledgements

- [Kadb](https://github.com/flyfishxu/Kadb)
- [android-sdk](https://github.com/HomuHomu833/android-sdk-custom)
- [libsu](https://github.com/topjohnwu/libsu)
- [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass)