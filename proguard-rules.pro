# ==============================================================================
# AntiDupePro - Optimized ProGuard Configuration
# ==============================================================================
# This configuration aggressively obfuscates code while preserving functionality.
# The mapping.txt file in build/proguard/ can decode stack traces.
# ==============================================================================

# ------------------------------------------------------------------------------
# 1. BASE SETTINGS
# ------------------------------------------------------------------------------

# Don't optimize (can cause issues with Kotlin coroutines and Bukkit reflection)
-dontoptimize

# Note: Do NOT use -dontpreverify! Java 7+ requires valid StackMapTable attributes.
# ProGuard must regenerate stackmap frames for bytecode verification to pass.

# Essential for shaded libraries with version conflicts
-ignorewarnings

# Suppress duplicate class notes from shaded dependencies
-dontnote **

# Flatten all obfuscated classes into a single package for harder analysis
-repackageclasses 'a'

# Allow ProGuard to modify access modifiers for better obfuscation
-allowaccessmodification

# Don't use mixed-case class names (Windows filesystem compatibility)
-dontusemixedcaseclassnames

# ------------------------------------------------------------------------------
# 2. ATTRIBUTE HANDLING
# ------------------------------------------------------------------------------

# Remove source file info from stack traces (hide that it's Kotlin)
-renamesourcefileattribute ""

# Keep essential attributes for reflection, debugging, and bytecode verification
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeInvisibleAnnotations
-keepattributes Exceptions
-keepattributes StackMapTable

# Keep line numbers for crash debugging (optional - remove for max obfuscation)
-keepattributes LineNumberTable

# ------------------------------------------------------------------------------
# 3. ENTRY POINTS - Bukkit Plugin Requirements
# ------------------------------------------------------------------------------

# Main Plugin Class (referenced in plugin.yml - MUST keep name and constructor)
-keep class io.github.darkstarworks.AntiDupePro {
    <init>();
}

# Plugin lifecycle methods
-keepclassmembers class * extends org.bukkit.plugin.java.JavaPlugin {
    public void onEnable();
    public void onDisable();
    public void onLoad();
}

# Event Listeners - Bukkit finds these via @EventHandler annotation
-keepclassmembers class * implements org.bukkit.event.Listener {
    @org.bukkit.event.EventHandler <methods>;
}

# Command Executors - registered in plugin.yml
-keep class * implements org.bukkit.command.CommandExecutor {
    public boolean onCommand(org.bukkit.command.CommandSender, org.bukkit.command.Command, java.lang.String, java.lang.String[]);
}

# Tab Completers
-keepclassmembers class * implements org.bukkit.command.TabCompleter {
    public java.util.List onTabComplete(org.bukkit.command.CommandSender, org.bukkit.command.Command, java.lang.String, java.lang.String[]);
}

# ------------------------------------------------------------------------------
# 4. CODE-LEVEL OBFUSCATION CONTROL
# ------------------------------------------------------------------------------

# Support for our custom @Keep annotation
-keep @com.server.antidupe.annotations.Keep class * { *; }
-keepclassmembers class * {
    @com.server.antidupe.annotations.Keep <fields>;
    @com.server.antidupe.annotations.Keep <methods>;
}

# Also support standard annotations if present
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# ------------------------------------------------------------------------------
# 5. KOTLIN ESSENTIALS (Minimal keeps)
# ------------------------------------------------------------------------------

# Keep Kotlin Metadata for basic interop (reflection on Kotlin classes)
-keep class kotlin.Metadata { *; }

# Keep Kotlin reflection entry points
-keep class kotlin.reflect.jvm.internal.** { *; }

# Coroutines - keep infrastructure but allow shrinking
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Keep coroutine continuation methods (essential for suspend functions)
-keepclassmembers class * implements kotlin.coroutines.Continuation {
    <methods>;
}

# Suppress Kotlin warnings (let ProGuard shrink unused parts)
-dontwarn kotlin.**
-dontwarn kotlinx.**

# ------------------------------------------------------------------------------
# 6. LIBRARY SHRINKING (OkHttp, Lettuce, Netty)
# ------------------------------------------------------------------------------

# OkHttp - Let ProGuard shrink, suppress warnings
-dontwarn okhttp3.**
-dontwarn okio.**

# OkHttp platform detection (uses reflection)
-keepnames class okhttp3.internal.platform.** { *; }
-dontnote okhttp3.internal.platform.**

# ==============================================================================
# LETTUCE + NETTY + REACTOR - Keep all unobfuscated
# ==============================================================================
# These libraries are tightly coupled and use extensive reflection/Unsafe.
# Obfuscating any part breaks cross-library calls. Keep them all intact.
# Your plugin code will still be fully obfuscated.

-dontwarn io.lettuce.**
-dontwarn io.netty.**
-dontwarn reactor.**

# Keep ALL Lettuce classes (Redis client)
-keep class io.lettuce.** { *; }

# Keep ALL Netty classes (networking framework)
-keep class io.netty.** { *; }

# Keep ALL Reactor classes (reactive streams)
-keep class reactor.** { *; }

# JSON library
-dontwarn org.json.**

# ------------------------------------------------------------------------------
# 7. ENUM PRESERVATION
# ------------------------------------------------------------------------------

# Keep enum values() and valueOf() methods (used by Kotlin/Java enum handling)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ------------------------------------------------------------------------------
# 8. NATIVE METHODS
# ------------------------------------------------------------------------------

-keepclasseswithmembernames class * {
    native <methods>;
}

# ------------------------------------------------------------------------------
# 9. SERIALIZATION (Only if truly needed via reflection)
# ------------------------------------------------------------------------------

# Keep Serializable infrastructure if any classes implement it
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ------------------------------------------------------------------------------
# 10. AGGRESSIVE SHRINKING NOTES
# ------------------------------------------------------------------------------
#
# WHAT WILL BE OBFUSCATED (Good for security):
# - LicenseVerifier, FeatureGate, and all licensing logic
# - IsotopeManager, IsotopeStorage, IsotopeScanner internals
# - DupeAlertManager internals
# - All private/internal methods and fields
# - Data classes (LicenseInfo, FeatureFlags, etc.)
#
# WHAT WILL BE SHRUNK (Smaller JAR):
# - Unused Kotlin stdlib classes
# - Unused OkHttp classes
# - Unused Lettuce/Netty classes
# - Unused coroutine machinery
#
# IF THE PLUGIN CRASHES:
# 1. Check build/proguard/mapping.txt to decode the stack trace
# 2. Find the missing class/method
# 3. Add a specific -keep for ONLY that class/method
# 4. Do NOT add blanket -keep rules for entire packages
#
# ==============================================================================
