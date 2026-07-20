-keep class com.web.view.kitty.** { *; }
-dontwarn com.web.view.kitty.**

-keep class androidx.webkit.** { *; }
-dontwarn androidx.webkit.**

-keep class androidx.webgpu.** { *; }
-dontwarn androidx.webgpu.**

-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-keep class kotlin.coroutines.** { *; }
-dontwarn kotlin.coroutines.**

-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

-dontrepackage

-keep class com.android.tools.r8.RecordTag { *; }
-dontwarn com.android.tools.r8.RecordTag

-keepattributes RuntimeInvisibleAnnotations, RuntimeInvisibleParameterAnnotations, RuntimeInvisibleTypeAnnotations

-ignorewarnings