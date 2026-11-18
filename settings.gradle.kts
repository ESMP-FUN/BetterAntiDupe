rootProject.name = "BetterAntiDupe"

// The public API other plugins compile against, the shared code, and one adapter per group of
// Minecraft versions that behave differently. The root project packs them into the one jar.
include("api", "common", "adapter-v1_21", "adapter-v26_2", "adapter-v26_4")
