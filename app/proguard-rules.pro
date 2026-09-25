# 保留 kotlinx.serialization 生成的序列化器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.fysly.pomodoro.** {
    *** Companion;
}
-keepclasseswithmembers class com.fysly.pomodoro.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.fysly.pomodoro.**$$serializer { *; }
