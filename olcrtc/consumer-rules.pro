# Keep gomobile-generated Seq runtime classes (called via JNI GetStaticMethodID)
-keep class go.** { *; }

# Keep gomobile-generated Mobile and interface classes
-keep class mobile.** { *; }

# Keep anonymous implementations of gomobile callback interfaces
# (proxySocketProtector calls CallStaticIntMethod via JNI)
-keep class * implements mobile.SocketProtector { *; }
-keep class * implements mobile.LogWriter { *; }

# Keep the mobile classes in the olcrtc package as well
-keep class com.wireguard.android.olcrtc.** { *; }
