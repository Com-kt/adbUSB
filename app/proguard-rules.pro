-keep class com.adb.kitty.compose.** { *; }
-dontwarn com.adb.kitty.compose.**

-keep class androidx.webkit.** { *; }
-dontwarn androidx.webkit.**

-keep class androidx.webgpu.** { *; }
-dontwarn androidx.webgpu.**

-keep class okio.** { *; }
-dontwarn okio.**

-keep class dalvik.** { *; }
-dontwarn dalvik.**

-keep class org.** { *; }
-dontwarn org.**

-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-keep class kotlin.coroutines.** { *; }
-dontwarn kotlin.coroutines.**

-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

-keep class org.lsposed.hiddenapibypass.** { *; }
-dontwarn org.lsposed.hiddenapibypass.**

-keep class io.nayuki.qrcodegen.** { *; }
-dontwarn io.nayuki.qrcodegen.**

-keep class com.google.** { *; }
-dontwarn com.google.**

-keep class com.topjohnwu.** { *; }
-dontwarn com.topjohnwu.**

-keep class cafe.cryptography.** { *; }
-dontwarn cafe.cryptography.**

-keep class com.flyfishxu.kadb.** { *; }
-dontwarn com.flyfishxu.kadb.**

-keep class com.flyfish233.crypto.spake2.** { *; }
-dontwarn com.flyfish233.crypto.spake2.**

-keep class bin.mt.** { *; } 
-dontwarn bin.mt.**

-dontrepackage

-keep class com.android.tools.r8.RecordTag { *; }
-dontwarn com.android.tools.r8.RecordTag

-keepattributes RuntimeInvisibleAnnotations, RuntimeInvisibleParameterAnnotations, RuntimeInvisibleTypeAnnotations

-ignorewarnings