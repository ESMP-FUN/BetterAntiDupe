# Changelog

All notable changes to AntiDupePro will be documented in this file.

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
