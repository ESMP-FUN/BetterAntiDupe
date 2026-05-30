# Changelog

All notable changes to AntiDupePro will be documented in this file.

## [3.0.0] - 2026-05-31

### Removed
- **Breaking**: the legacy per-item-NBT isotope tracking system (v1) has been
  removed entirely. The Chain of Custody ledger (formerly v2) is now the sole
  detection layer. The visible benefit: vanilla item stacking works correctly
  again — diamonds, netherite ingots and all other tracked items merge into
  single stacks as they should.
- `/adp inspect`, `/adp dupetest` and `/adp cleanse` commands removed (they
  were specific to the isotope system).
- `ledger.enabled` config option removed (the ledger is always on).

### Added
- **Folia compatibility.** Detects Folia at runtime and dispatches scheduler
  calls to the regional schedulers; falls back to `BukkitScheduler` on Paper
  non-Folia and Spigot. `plugin.yml` declares `folia-supported: true`.
- **Spigot compatibility.** All scheduler access goes through the reflection-
  based abstraction, so the same jar loads on Paper, Folia and Spigot. No NMS
  references anywhere.
- **Balance read-through cache.** Hot reconciliation queries no longer round-
  trip to the storage backend on every call — appends bump the cached entry
  atomically inside the chain-tip mutex.

### Changed
- Audit-trail vocabulary unchanged; existing ledger entries remain readable.
- Storage backends still pluggable (SQLite default, Redis, Memory).

### Migration
Existing 2.x installs running v1 isotope data (the `isotopes` table in
`storage.db` or `iso:*` keys in Redis) can safely keep that data — the plugin
no longer reads it. To reclaim disk space you can drop the `isotopes` table
or `FLUSHDB` the relevant Redis database; nothing in 3.0.0 references those
keys.

## [2.7.0] - 2026-05-30

### Added
- Per-entity pickup history: every item-entity UUID is recorded the first time
  it's picked up. If the same UUID is ever picked up again — which only happens
  for chunk-load dupes, drop-pickup race dupes, and cross-server-race dupes —
  the second pickup is flagged as a CRITICAL dupe alert and is not credited to
  the player's ledger balance.
- Pickup history is persisted across server restarts and prunes automatically
  after 30 days. Implemented across all three storage backends (SQLite uses a
  new pickup_history table; Redis uses SET NX with a 30-day TTL; in-memory
  uses ConcurrentHashMap).

### Changed
- Closes the last remaining family on the original dupe taxonomy: chunk-save
  entity respawn dupes are now detected with the same per-entity ledger that
  catches drop-pickup races.

## [2.6.0] - 2026-05-30

### Fixed
- Long-standing double-counting bug where mining a tracked block credited the
  player twice — once when the block broke and once when the dropped item was
  picked up. The ledger now credits the pickup only, and the originating event
  (mine, frame take, pot break) is recorded as the pickup's source. Same fix
  applies to intentional item-frame takes.
- Excess pickups from a single expected drop (the dupe surplus) are no longer
  credited to the ledger at all, so the resulting inventory-vs-ledger gap
  reinforces the immediate dupe alert with a balance-reconciliation hit on the
  next pass.

### Changed
- Ledger history entries for PICKUP now carry source attribution
  (e.g. `MINE:DIAMOND_ORE|TOOL:NETHERITE_PICKAXE`), so admins can see at a glance
  whether an acquisition came from mining, a frame, a pot break, or a generic
  ground pickup.

## [2.5.0] - 2026-05-30

### Added
- Decorated pot deposits are now recorded as container puts; the matching break
  registers the deposited stack as an expected drop, so deposit-then-break cycles
  net to zero in the ledger and any extra copies are flagged as dupes

### Changed
- The block-break handler now special-cases decorated pots — their contents are
  treated as previously-deposited items rather than fresh "mined" drops, which
  removes a double-counting source for legitimate pot use

## [2.4.0] - 2026-05-30

### Added
- Material lists now live in their own file, `materials.yml`, so `config.yml`
  stays short even when many items are tracked. Holds `tracked_materials`,
  `tmar_limits` and `alert_thresholds`.
- Automatic one-time migration: existing installs that had these keys in
  `config.yml` get them moved to `materials.yml` on first start of this
  version, and the legacy keys are removed from `config.yml`.

### Changed
- User guide updated to document the new configuration file layout.

## [2.3.0] - 2026-05-30

### Added
- Workstation output tracking for smithing tables, anvils, looms, stonecutters,
  cartography tables and grindstones (closes the workstation-output blind spot for
  these recipes)
- Furnace, smoker and blast furnace output tracking on player extract
- Lectern book-take tracking (closes lectern swap dupe family)
- Ender chest transfers now recorded as container put/take
- Bundle content scanning: items stored inside bundles are now inspected just like
  shulker box contents, so duped items can no longer be laundered through bundles

### Changed
- Audit-trail vocabulary extended with STATION_OUTPUT action

## [2.2.0] - 2026-05-30

### Added
- Item-frame transaction tracking: placing into and removing from frames is now ledgered
- Item-frame drop accounting: frames register exactly one expected drop on break, and any
  surplus pickups in that area trigger a high-severity alert (closes the piston / chunk-race
  item-frame dupe family)
- Entity-inventory tracking for horses, donkeys, llamas, mules, chest boats and chest minecarts
- Public API `ChainOfCustody.recordSystemGrant(...)` for other plugins to declare legitimate
  item grants and avoid false-positive reconciliation hits

### Changed
- Audit-trail vocabulary extended with FRAME_PUT, FRAME_TAKE, ENTITY_PUT, ENTITY_TAKE actions

## [2.1.0] - 2026-05-30

### Added
- Pluggable storage backend with three options: file-based (default), networked, and in-memory
- File-based storage works out of the box — no external services required
- New user guide covering install, configuration, and feature walkthroughs
- Configurable thresholds, cooldowns, and witness radius now read from config

### Changed
- Targets Paper 1.21.x (verified up to 1.21.11)
- Default install path no longer depends on any external service
- Audit trail writes are now serialised so concurrent events keep the chain intact

### Fixed
- Network connection identifiers built correctly when a password is set
- Background workers shut down cleanly on plugin disable / server reload
- Container transfers logged with correct direction and quantity
- Shift-craft amount no longer over-counts when the inventory is nearly full
- Witness history collections are now safe for concurrent access
- Large-scope key lookups replaced with cursored scans to avoid backend stalls
- Recent-acquisition checks no longer make per-entry round-trips
- Inventory snapshots are cleaned up when a player disconnects mid-session
- Offline player lookups no longer block the calling thread

### Removed
- External license verification and feature gating
- Build-time obfuscation pass (release artefact is now a plain shaded jar)

## [2.0.0] - 2024-12-07

### Added
- Next-generation protection engine with enhanced detection capabilities
- Distributed validation system for improved accuracy
- Player trust scoring based on behavioral patterns
- Advanced activity monitoring with proximity awareness
- Balance verification system for comprehensive item tracking
- Admin tools for investigating suspicious activity (`/adp ledger`)
- Chain integrity verification for data consistency
- Configurable detection sensitivity settings

### Changed
- Items now stack normally with the new protection system
- Improved performance with optimized data structures
- Enhanced suspect tracking with detailed violation history

### Technical
- New protection system runs alongside existing for gradual migration
- Separate database storage for new system (configurable)
- Automatic maintenance and cleanup tasks

## [1.0.3] - 2024-12-07

### Added
- New admin command to reset item security status
- Fixes issue where flagged items couldn't be merged

## [1.0.2] - 2024-12-07

### Changed
- Consolidated all commands under `/adp` base command
- Added tab completion for subcommands
- Added command aliases for convenience

## [1.0.1] - 2024-12-07

### Added
- New admin command for testing and demonstration purposes
- Enhanced feedback messages for protected items

### Changed
- Improved internal identifiers for better compatibility
- Refined user-facing terminology for clarity

### Fixed
- Minor stability improvements

## [1.0.0] - 2024-12-06

### Added
- Initial release
- Core protection system for valuable items
- Real-time monitoring and detection
- Admin inspection tools
- Configurable item tracking
- Rate limiting for suspicious activity
- Shadow mode for silent observation
- Redis backend integration
- License verification system
