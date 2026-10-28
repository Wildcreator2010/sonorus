# ---------- 日志剥离（release）：避免任何途径泄露密钥、请求体、用户内容 ----------
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int println_native(...);
}
-assumenosideeffects class java.io.PrintStream {
    public void println(java.lang.String);
    public void print(java.lang.String);
}

# ---------- kotlinx.serialization ----------
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <methods>;
}
-keep,includedescriptorclasses class com.wildcreator.sonorus.**$$serializer { *; }

# ---------- OkHttp / Okio ----------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.slf4j.**
-keepattributes Signature
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ---------- AndroidX Security（Keystore 加密存储）----------
-keep class androidx.security.crypto.** { *; }
-dontwarn com.google.crypto.tink.**

# ---------- Room ----------
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
