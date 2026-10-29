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

# Tink（androidx.security 的底层）：它的源码里用了一组 errorprone 注解，
# 而这些注解库是 compileOnly，运行时根本不存在。
# R8 会把「引用了一个不存在的类」当错误终止构建，报的是一堆
# com.google.errorprone.annotations.* —— 看着像缺依赖，其实是纯注解噪声。
# 它们只在编译期起作用，dontwarn 掉即可，不影响加密的实际行为。
-dontwarn com.google.errorprone.annotations.**

# Room
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# 实体由 Room 生成代码在运行时读取
-keep @androidx.room.Entity class * { *; }