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
-keep class org.conscrypt.** { *; }
-dontwarn com.flyfishxu.kadb.**
-dontwarn org.conscrypt.**

-keep class bin.mt.** { *; } 

-dontrepackage

-keepattributes RuntimeInvisibleAnnotations, RuntimeInvisibleParameterAnnotations, RuntimeInvisibleTypeAnnotations

-ignorewarnings