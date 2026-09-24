# Haven release rules. Keep names out of crash text is not a goal; we keep stack traces readable
# because release builds ship no crash reporter and users may send us traces manually.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# SQLCipher (JNI entry points)
-keep class net.zetetic.database.** { *; }
# Argon2kt (JNI entry points)
-keep class com.lambdapioneer.argon2kt.** { *; }
# Tink registers key managers reflectively in places
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
# Remove all Log calls from release builds (belt and braces; Haven's logger is already a no-op).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# kotlinx.serialization: keep generated serializers and @Serializable classes' companions.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.norypt.haven.**$$serializer { *; }
-keepclassmembers class com.norypt.haven.** { *** Companion; }
-keepclasseswithmembers class com.norypt.haven.** { kotlinx.serialization.KSerializer serializer(...); }
