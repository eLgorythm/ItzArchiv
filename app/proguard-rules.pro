# ItzArchiv — aturan R8/ProGuard buat build release

# Komponen aplikasi sendiri jangan dibuang/diobfuscate berlebihan
-keep class me.fndlabs.itzarchiv.** { *; }
-keepclassmembers class me.fndlabs.itzarchiv.** { *; }

# Commons Compress: sebagian jalan lewat refleksi/ServiceLoader internal
-keep class org.apache.commons.compress.** { *; }

# JunRAR
-keep class com.github.junrar.** { *; }

# Zip4j
-keep class net.lingala.zip4j.** { *; }

# XZ for Java
-keep class org.tukaani.xz.** { *; }

# Abaikan dependensi opsional yang memang tidak dibundel
-dontwarn org.objectweb.asm.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.**
-dontwarn com.aayushatharva.brotli4j.**
-dontwarn org.osgi.**
-dontwarn javax.**
-dontwarn org.slf4j.**
-dontnote **
