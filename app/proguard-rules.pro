-keep class com.adb.kitty.compose.** { *; }
-dontwarn com.adb.kitty.compose.**

-keep class dalvik.** { *; }
-dontwarn dalvik.**

-keep class cafe.cryptography.** { *; }
-dontwarn cafe.cryptography.**

-keep class okio.** { *; }
-dontwarn okio.**

-keep class org.** { *; }
-dontwarn org.**

-keep class android.** { *; }
-dontwarn android.**

-keep class net.sf.sevenzipjbinding.** { *; }
-dontwarn net.sf.sevenzipjbinding.**
-keep interface net.sf.sevenzipjbinding.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

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

-ignorewarnings