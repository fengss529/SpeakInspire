# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Moshi：反射序列化需要保留 data class 的构造器与字段
-keep class com.hatio.speakinspire.data.model.** { *; }
-keepclassmembers class com.hatio.speakinspire.data.model.** {
    <init>(...);
}
-keep class kotlin.Metadata { *; }

# SSE 用到的okhttp-sse 不再额外处理

# Room
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# 实体由 Room 生成代码在运行时读取
-keep @androidx.room.Entity class * { *; }