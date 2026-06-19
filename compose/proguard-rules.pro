-keep class com.adb.kitty.compose.** { *; }
-dontwarn com.adb.kitty.compose.**

-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-keep class kotlin.coroutines.** { *; }
-dontwarn kotlin.coroutines.**

-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

-keep class org.lsposed.hiddenapibypass.** { *; }
-dontwarn org.lsposed.hiddenapibypass.**

-keep class com.topjohnwu.** { *; }
-dontwarn com.topjohnwu.**

-keep class com.flyfishxu.kadb.** { *; }
-dontwarn com.flyfishxu.kadb.**

-keep class bin.mt.** { *; } 
-dontwarn bin.mt.**

-dontrepackage

-keep class com.android.tools.r8.RecordTag { *; }
-dontwarn com.android.tools.r8.RecordTag

-keepattributes RuntimeInvisibleAnnotations, RuntimeInvisibleParameterAnnotations, RuntimeInvisibleTypeAnnotations

-ignorewarnings com.github.L-JINBIN:MTDataFilesProvider
-ignorewarnings com.flyfishxu:kadb
-ignorewarnings org.jetbrains.kotlinx:kotlinx-coroutines-core
-ignorewarnings com.github.topjohnwu.libsu:service
-ignorewarnings com.github.topjohnwu.libsu:io
-ignorewarnings io.nayuki:qrcodegen
-ignorewarnings