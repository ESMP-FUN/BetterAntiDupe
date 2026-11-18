# =============================================================================
# BetterAntiDupe ProGuard configuration
# Shrink and optimize only. Names stay as they are, so error reports stay
# readable and no mapping file is needed.
#
# The build hands ProGuard the Spigot API ahead of the Paper API. ProGuard
# works out new stack frames from whichever copy of a class it reads first, and
# with Paper's it can name a Paper-only type (Translatable, which Paper's
# Material implements and Spigot's does not), which Spigot refuses to load.
# =============================================================================
-dontobfuscate
-optimizations !class/merging/*
-dontnote **

# Plugin entry point, named in plugin.yml.
-keep class com.esmpfun.antidupe.BetterAntiDupe { public *; }

# The public API other plugins compile against: every class, unchanged.
-keep class com.esmpfun.antidupe.api.** { *; }

# Adapters are created by name only (ServerAdapters), so nothing else would keep them.
-keep class * implements com.esmpfun.antidupe.adapter.ServerAdapter { public <init>(); }

# Bukkit finds event handlers by reflection.
-keepclassmembers class * implements org.bukkit.event.Listener {
    @org.bukkit.event.EventHandler <methods>;
}

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Libraries that find their own parts by reflection, ServiceLoader or field
# updaters. Kept whole so the Redis backend, update checks and metrics behave
# exactly as they were written; only our own code and Kotlin's are trimmed.
-keep class com.esmpfun.antidupe.libs.** { *; }
-keep class io.netty.** { *; }
-keep class io.github.darkstarworks.adp.pluginpulse.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class org.reactivestreams.** { *; }
-keep class org.jspecify.** { *; }
-keep class org.slf4j.** { *; }
-keep class redis.clients.** { *; }

-keepattributes Exceptions, InnerClasses, Signature, EnclosingMethod, *Annotation*, SourceFile, LineNumberTable, RuntimeVisible*Annotations

# The server provides these, and the libraries above name optional parts we do not ship.
-dontwarn org.bukkit.**
-dontwarn io.papermc.**
-dontwarn com.destroystokyo.**
-dontwarn net.kyori.**
-dontwarn net.md_5.**
-dontwarn io.netty.**
-dontwarn com.esmpfun.antidupe.libs.**
-dontwarn io.github.darkstarworks.adp.pluginpulse.**
-dontwarn kotlinx.coroutines.**
-dontwarn org.jetbrains.annotations.**
-dontwarn org.jspecify.**
-dontwarn org.slf4j.**
-dontwarn redis.clients.**
-dontwarn kotlin.**
-dontnote
