-keep class com.adb.kitty.compose.** { *; }

# 1. 针对 LSPosed HiddenApiBypass 的保持规则
# 它通过非对称反射修改系统多不安全区域，坚决不能被任何优化移动

-keep class org.lsposed.hiddenapibypass.** { *; }
-dontwarn org.lsposed.hiddenapibypass.**

# 2. 针对 libsu (TopJohnWu 大神的系统 Root 权限库) 
# 内部极度依赖大量的进程守护、反射和 AIDL 跨进程通信

-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# 3. 针对 KADB (FlyfishXu) 以及 Conscrypt Uber 安全库
# 内部涉及底层 TCP/IP 套接字反射及加密安全提供者 (Security Provider)

-keep class com.flyfishxu.kadb.** { *; }
-keep class org.conscrypt.** { *; }
-dontwarn com.flyfishxu.kadb.**
-dontwarn org.conscrypt.**

# 4. 针对 MT 框架自身的数据提供者
# 注意：请根据您 toml 里的实际根包名微调，通常是形如下方的格式
-keep class bin.mt.** { *; } 

# 5. AGP 9.2+ 必须补充的全局反射安全兜底
# 强行禁止对上述库执行重打包移动，防止 Class.forName 找不到原本的路径
-dontrepackage

# 强制保留所有运行时的不可视注解，确保反射框架行为正常
-keepattributes RuntimeInvisibleAnnotations, RuntimeInvisibleParameterAnnotations, RuntimeInvisibleTypeAnnotations
