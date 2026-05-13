# Keep gomobile-generated Seq runtime classes (called via JNI GetStaticMethodID)
-keep class go.** { *; }

# Keep gomobile-generated Mobile and interface classes
-keep class mobile.** { *; }
# Force clean rebuild Wed May 13 23:13:14 UTC 2026
