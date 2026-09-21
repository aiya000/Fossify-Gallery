-keep class org.fossify.** { *; }
-dontwarn android.graphics.Canvas
-dontwarn org.fossify.**
-dontwarn org.apache.**

# Picasso
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn org.codehaus.mojo.animal_sniffer.*
-dontwarn okhttp3.internal.platform.ConscryptPlatform

-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

# RenderScript
-keepclasseswithmembernames class * {
native <methods>;
}
-keep class androidx.renderscript.** { *; }

# Reprint
-keep class com.github.ajalt.reprint.module.** { *; }

# smbj, and what it pulls in. The client picks its dialects, authenticators and crypto
# implementations through named factories it looks up at runtime, so R8 cannot see who uses
# them; bouncycastle is the provider behind SMB3 encryption and mbassador dispatches by
# annotated method
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn com.hierynomus.**
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn org.slf4j.**

# mbassador can filter messages with Java EE expression language, which Android has no
# implementation of; that path is never taken here
-dontwarn javax.el.**
