# `paper-26` branch

This branch tracks the **paper-26.x** release line of Paper / Minecraft. The
codebase is otherwise identical to `main` — the only differences are in
build configuration:

| File | `main` | `paper-26` |
|---|---|---|
| `build.gradle.kts` — `compileOnly paper-api` | `1.21.8-R0.1-SNAPSHOT` | `26.1.2.build.66-stable` |
| `build.gradle.kts` — `targetJavaVersion` | `21` | `25` |
| `build.gradle.kts` — `runServer minecraftVersion` | `1.21.11` | `26.1.2` |
| `src/main/resources/plugin.yml` — `api-version` | `'1.21'` | `'26'` |
| Plugin version suffix | none | `-paper26` |

## Why a separate branch instead of a flag?

Paper-26 requires **Java 25**, while `main` targets **Java 21** for broad
compatibility with the 1.21.x server fleet. Trying to keep both in one branch
would either force everyone onto Java 25 (cutting off 1.21.x users running
older JVMs) or require build profiles that complicate every PR.

A branch is the cheapest way to keep the two release lines clean. Feature
changes should land on `main` first, then cherry-pick into `paper-26`. The
intent is that the source under `src/main/kotlin/` stays identical between
the branches — divergence here would be a sign that one branch has accreted
platform-specific code that should be guarded behind reflection or extracted
to a platform layer instead.

## Initial port — May 2026

The port from `main` (paper-api 1.21.8) to paper-api 26.1.2 required **zero
source changes**. Every API surface we depend on was preserved:

- `ItemStack` / `ItemMeta` / `PersistentDataContainer` — unchanged at the
  method level (deprecations exist for old constructors and `setType`, but
  we use neither)
- All event types we listen for (`InventoryClickEvent`,
  `PlayerInteractEntityEvent`, `HangingBreak*Event`, `BlockBreakEvent`,
  `FurnaceExtractEvent`, `PlayerTakeLecternBookEvent`, etc.) — unchanged
- `DecoratedPotInventory`, `BundleMeta`, `BlockStateMeta` — unchanged
- Container holder types (`AbstractHorse`, `Boat` → `ChestBoat`,
  `StorageMinecart`, `HopperMinecart`) — unchanged
- Folia scheduler classes — same package path, accessed via reflection in
  `PlatformScheduler` so the loader sees no compile-time dependency

If you find a future paper-26 API break, document it here and apply the fix
under a `// paper-26: …` comment.
