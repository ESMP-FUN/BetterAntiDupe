# User Guide

BetterAntiDupe is a Paper, Folia and Spigot plugin that stops item duplication
on Minecraft servers running **1.21.x** (tested through 1.21.11) and **26.x** —
one codebase, two downloads (plain jar for 1.21.x, `-mc26` jar for 26.x). It
tags valuable items with the owner's UUID and writes every acquisition and loss
to a tamper-evident ledger, so when a player's actual inventory diverges from
what their ledger says they should hold, the plugin alerts.

This guide is written for server owners and admins. You do not need to
understand how the detection works to use the plugin — just install it, pick a
storage backend, and the defaults will do the right thing.

## 1. What it does

Whenever a player mines, crafts, picks up, or otherwise obtains a tracked item
(diamonds, netherite, shulkers, elytras, etc.), BetterAntiDupe tags the item
with the player's UUID and records the event in an append-only ledger. Every
loss event — placing a block, dropping an item, putting something into a chest
or frame — is recorded too. If the player's actual inventory diverges from what
the ledger sums to, the plugin alerts.

Detection runs through the **Chain of Custody** ledger. Every entry contains a
SHA-256 hash linking it to the previous entry, so the chain is tamper-evident —
anyone editing the database directly breaks the chain, and `/adp ledger verify`
reports exactly where. Nearby players are recorded as witnesses, so dupe
exploits that bypass normal events leave behind a telltale "no witnesses ever
saw this item appear" pattern that `/adp ledger witness` surfaces.

Items stack normally (vanilla behaviour preserved) because tracking attaches to
the owner UUID, not to per-item identifiers.

## 2. Quick start (5 minutes)

1. Download the latest `BetterAntiDupe-X.Y.Z.jar` (1.21.x) or
   `BetterAntiDupe-X.Y.Z-mc26.jar` (26.x).
2. Drop it into your server's `plugins/` folder.
3. Start the server once. The plugin generates
   `plugins/BetterAntiDupe/config.yml` and a SQLite database file, then loads
   itself.
4. That's it. Your server now tracks the default set of valuable items.

To confirm it's working, mine a diamond block, then run
`/adp ledger balance <your-name>`. You should see `DIAMOND_BLOCK: +1` in the
output.

## 3. Installation

### Requirements

| Item | Requirement |
|---|---|
| Server software | Paper, Folia, Spigot, or a Paper-compatible fork (Purpur, Pufferfish, etc.) |
| Minecraft version | 1.21.0 – 1.21.11 (plain jar) · 26.x (`-mc26` jar) |
| Java | 21 or newer for 1.21.x · 25 or newer for 26.x |
| External services | None required (SQLite is built in). Redis is optional. |

### Steps

1. Stop your server.
2. Place `BetterAntiDupe-X.Y.Z.jar` (1.21.x) or `BetterAntiDupe-X.Y.Z-mc26.jar`
   (26.x) in `plugins/`.
3. Start the server. On first boot you will see something like this in the
   console:

   ```
   [BetterAntiDupe] === BetterAntiDupe v3.3.2 ===
   [BetterAntiDupe] Initializing Chain of Custody...
   [BetterAntiDupe] ✓ Scheduler initialized (Bukkit mode)
   [BetterAntiDupe] ✓ Configuration loaded
   [BetterAntiDupe] [Ledger] Connected to SQLite at ledger.db
   [BetterAntiDupe] ✓ Chain of Custody initialized
   [BetterAntiDupe]   Tracking 7 materials, 5 TMAR limits
   [BetterAntiDupe] === BetterAntiDupe enabled successfully ===
   ```

4. Open `plugins/BetterAntiDupe/config.yml` and adjust anything you want (see
   the [configuration reference](#5-configuration-reference)).
5. Reload the plugin with `/reload confirm` or restart the server.

### Upgrading from an older version

Stop the server, replace the jar, start the server. Your existing `config.yml`
is preserved. If new config keys are introduced in a release, the plugin uses
sensible defaults for them — check the changelog to see if you want to add the
new keys explicitly.

## 4. Choosing a storage backend

BetterAntiDupe can store its tracking data in one of three places. Choose the
one that fits your setup:

| Backend | When to use | Pros | Cons |
|---|---|---|---|
| **SQLite** _(default)_ | Single-server setups, almost everyone. | No external service. Data survives restarts. Zero configuration. | Not shared between servers in a network. |
| **Redis** | Multi-server networks where item tracking must span servers. | Fast. Shared state across a network. Battle-tested. | You have to run and maintain a Redis instance. |
| **Memory** | Testing and development only. | Instant, zero setup. | **Data is lost on restart.** Do not use in production. |

Set your choice in `config.yml`:

```yaml
storage:
  backend: SQLITE    # or REDIS, or MEMORY
  sqlite_file: "storage.db"
  sqlite_ledger_file: "ledger.db"
```

If you pick `REDIS`, fill in the `redis:` section further down in the config
(host, port, password, database).

## 5. Configuration reference

Configuration is split across two files in `plugins/BetterAntiDupe/`:

- `config.yml` — plugin behaviour: storage backend, modes, ledger settings.
- `materials.yml` — the lists of tracked items, rate limits, and alert
  thresholds. Kept separate so this file can grow long without cluttering the
  main config.
- `messages.yml` — every in-game message (since 3.3.3). Translate or restyle
  freely; see below.

Existing installs that already have `tracked_materials`, `tmar_limits` or
`ledger.alert_thresholds` defined in `config.yml` are migrated automatically on
first start of this version — the values move into `materials.yml` and are
removed from `config.yml`. You don't need to do anything.

### config.yml

```yaml
storage:
  # Where to keep tracking data. SQLITE, REDIS, or MEMORY.
  backend: SQLITE
  # SQLite filename for the ledger database, inside plugins/BetterAntiDupe/.
  sqlite_ledger_file: "ledger.db"

redis:
  # Used only when storage.backend: REDIS.
  host: "localhost"
  port: 6379
  database: 0
  password: ""
  timeout: 10        # seconds

# Tracked items, rate limits and alert thresholds live in materials.yml.

# ---------- Duper prevention (block-duplication contraptions) ----------
# Blocks the classic dupe machines at the mechanic level, before any item
# exists to track: rail/carpet dupers (piston movement under an attached
# rail/carpet is cancelled), TNT dupers (pistons can't move TNT), gravity
# dupers (falling blocks can't travel through portals), and "phantom GUI"
# container dupes (open GUIs are closed when their shulker/chest is
# destroyed or their donkey/chest-boat unloads). All on by default.
prevent-rail-dupers: true
prevent-carpet-dupers: true
prevent-gravity-dupers: true
prevent-tnt-dupers: true
prevent-container-desync-dupers: true

# If true, the plugin silently removes detected duplicates from inventories.
# If false (default), it only alerts admins and logs the event.
auto_delete_dupes: false

# In shadow mode, suspected dupers are watched and their stash locations
# are logged, but they aren't punished automatically.
shadow_mode: true

# Cancel "double-click to gather all" on tracked items. Off by default
# because it interferes with normal play; only useful in strict paranoia
# setups.
block_collect_to_cursor: false

# Hide the ownership tag from players' clients. On by default. The plugin removes
# its own tag from items in the packets sent to players, so a client-side NBT
# viewer can't read it. The tag stays intact server-side, so detection is
# unchanged. Set false only if you need the tag visible client-side.
hide_tag_from_clients: true

# Strict mode: strip EVERY plugin's custom item data from packets, not just
# BetterAntiDupe's tag. Off by default — CIT resource packs and client mods that
# read item data will see stripped items as blank. Whitelist the namespaces
# your pack/mods need (BetterAntiDupe's own namespace is never allowed).
strip_all_custom_data: false
strip_whitelist: []

# Rename the ownership tag so leaked screenshots/streams don't reveal which
# plugin wrote it (e.g. "data:o" instead of "antidupepro:adp_owner"). Renaming
# is safe: the old name is remembered automatically and existing items stay
# tracked, re-stamping onto the new name as they change hands.
ownership:
  namespace: "antidupepro"
  key: "adp_owner"
  legacy_keys: []

detection:
  # Global sensitivity, 1 (very lenient) to 100 (very paranoid). 50 is balanced
  # and right for most casual servers. Higher = a smaller surplus alerts.
  sensitivity: 50
  # Optional command run on the console when an admin confirms a duper with
  # /adp ledger confirm. {player} is replaced with the name. Leave empty to do
  # nothing. Example: "tempban {player} 7d Item duplication"
  on_confirm_command: ""

# Console verbosity: CRITICAL, ERROR, WARNING, INFO, DEBUG (each includes the
# levels above it). CRITICAL and ERROR both map to the server's SEVERE level.
console_log_level: INFO

# ---------- v2 Chain of Custody ----------
ledger:
  # Turn the v2 system on. Off by default while it matures.
  enabled: false

  # Redis database number for the ledger (used only with REDIS backend).
  redis_database: 1

  witness:
    # How many blocks away a player has to be to "witness" an action.
    radius: 48
    # How many witnesses an action needs to count as VERIFIED.
    verified_threshold: 3
    # Players whose actions are over this fraction unwitnessed get flagged.
    suspicious_solo_ratio: 0.8

  reconciliation:
    # How long to wait between balance checks per player, in milliseconds.
    cooldown_ms: 5000

  # Alert thresholds are configured in materials.yml.
```

### materials.yml

```yaml
# Items that get tracked. Add or remove freely; use vanilla Material names.
# Shulker boxes of every color are always tracked regardless of this list.
tracked_materials:
  - DIAMOND_BLOCK
  - NETHERITE_INGOT
  - BEACON
  - ENCHANTED_GOLDEN_APPLE
  - SHULKER_BOX
  - ELYTRA
  - NETHER_STAR

# Maximum tracked items per minute. Players exceeding these are flagged
# as suspicious (Theoretical Max Acquisition Rate).
tmar_limits:
  ANCIENT_DEBRIS: 20
  DIAMOND_BLOCK: 10
  ENCHANTED_GOLDEN_APPLE: 2
  NETHERITE_INGOT: 15
  BEACON: 5

# How many extra items a player can hold above their ledger balance
# before an alert fires (v2 ledger only). Higher-value items have
# stricter thresholds. 'default' applies to materials not listed here.
alert_thresholds:
  ENCHANTED_GOLDEN_APPLE: 1
  BEACON: 1
  NETHER_STAR: 1
  ELYTRA: 1
  NETHERITE_INGOT: 2
  DIAMOND_BLOCK: 3
  default: 5
```

### Webhook notifications & translation

Two features have their own short guide —
[Notifications & Translation](notifications-and-translation.md):

- **Alerts outside the game (3.3.4+)** — push dupe alerts to Discord, Telegram,
  Slack, or any custom webhook. Step-by-step setup per service, all off by
  default, configured in the `notifications` section of `config.yml`.
- **Translating the plugin (3.3.3+)** — every in-game message lives in
  `messages.yml`; edit any line, deleted lines fall back to English. Since
  3.3.5, five translations are built in — set `language: pt_BR` / `es` / `de` /
  `ru` / `pl` in config.yml.

## 6. Features

### 6.1 How detection works

Every tracked item entering a player's inventory gets the player's UUID written
to its NBT. Every gain (mine, craft, pickup, container take, workstation
output, …) and every loss (place, drop, container put, consume, …) is appended
to a per-server ledger, with each entry cryptographically linked to the
previous one. Reconciliation walks the player's inventory — recursively,
including inside held shulkers and bundles — and compares the total to the
ledger sum. A surplus is a dupe.

### 6.2 What it catches

- **Balance reconciliation.** The plugin periodically counts how many of each
  tracked material the player has in their inventory and compares it to the
  ledger total. If a player has 20 diamond blocks but the ledger only shows
  them ever gaining 12, that's a dupe.
- **Proof of Witness.** When a player mines, crafts, or picks up something, the
  plugin records nearby players as witnesses. Players whose actions are
  _never_ witnessed on a populated server are statistically suspicious — that's
  exactly the pattern a dupe exploit produces.
- **Tamper detection.** Each ledger entry contains a SHA-256 hash linking it to
  the previous entry. Anyone editing the ledger directly (e.g. by SQL) breaks
  the chain, and `/adp ledger verify` reports exactly where.
- **Trust scores.** Players build a long-term trust score based on how often
  their actions are independently corroborated by witnesses.
- **Item-frame dupe detection.** Every frame break registers exactly one
  expected drop. Any surplus pickup in the same area within 60 seconds fires a
  high-severity alert — closes the piston-into-frame and chunk-race frame dupe
  families.
- **Per-entity pickup history.** Every dropped item entity is recorded the
  first time it's picked up. If the same physical entity ever gets picked up
  again — which has no innocent explanation outside of a server crash
  recovery — the second pickup is flagged CRITICAL and not credited. Closes
  chunk-load entity dupes, drop-pickup race dupes, and proxy-network race
  dupes.
- **Workstation and storage coverage.** Smithing tables, anvils, looms,
  stonecutters, cartography tables, grindstones, furnaces, smokers, blast
  furnaces, lecterns, ender chests, decorated pots, horse/donkey/llama chests,
  chest boats and chest minecarts all track item movement correctly. Inputs and
  outputs reconcile. Since 3.3.2 this includes **double chests**, **villager
  trades** and **enchanting a book**.
- **Accurate chest accounting (3.3.2).** Moving items with shift-clicks, number
  keys, offhand swaps, double-click gathering or drag-moves is recorded by what
  _actually_ moved — not by what the click "should" have moved. Fewer false
  alarms, no loopholes.
- **Bundle content scanning.** Items stored inside bundles are inspected just
  like shulker contents, so duped items can't be laundered through bundles or
  the shulker-dye crafting recipe vector.
- **Deep container reconciliation.** The balance check recursively descends
  into shulker boxes, barrels, chests-stored-as-items and bundles at every
  nesting level. A player carrying a duped shulker full of diamonds used to
  show a clean balance against an empty main inventory — the deep scan now
  counts the contents and surfaces the discrepancy.
- **Hopper laundering.** Items moving through hopper automation are scanned, so
  duped items can't be washed by feeding them through chest networks.
- **Acquisition rate abuse (TMAR).** If a player suddenly starts gaining far
  more diamonds-per-minute than is physically possible, the plugin flags it.

### 6.3 Shadow mode and alerts

By default, the plugin runs in _shadow mode_: it watches and records, but
doesn't immediately delete items or punish players. When something suspicious
happens:

- Admins online get an in-game alert in chat.
- The event is logged to the server console.
- If the player is flagged, the next chest they open is logged too — letting
  you find their stash for manual review.

If you'd rather take action automatically, set `auto_delete_dupes: true`. The
plugin will silently remove detected duplicates from inventories.

## 7. Commands

All commands live under `/antidupe` (aliases: `/adp`, `/betterantidupe`).

Two top-level commands: `/adp help` (no permission) and `/adp ledger ...`
(requires `antidupe.ledger`). Everything else is a ledger subcommand.

### Ledger commands

| Command | What it does |
|---|---|
| `/adp ledger status` | Show overall ledger state: chain tip, recent hash, current suspect count. |
| `/adp ledger balance <player>` | Show the player's expected balances for each tracked material. |
| `/adp ledger history <player>` | Show the player's most recent 15 ledger entries. |
| `/adp ledger witness <player>` | Show the player's witness statistics and any suspicious patterns. |
| `/adp ledger suspects` | List all currently flagged players, ranked by violation count. |
| `/adp ledger stash <player>` | Show where the player stashed tracked items, with clickable coordinates that teleport you to each stash (added in 3.2.0). |
| `/adp ledger reconcile <player>` | Force a balance check for an online player right now. |
| `/adp ledger trust <player>` | Show the player's accumulated trust score. |
| `/adp ledger confirm <player>` | Verdict: confirm a real duper. Pins their suspicion high (future hits trip on far less) and runs `detection.on_confirm_command` if set. |
| `/adp ledger clear <player>` | Verdict: mark a false positive. Resets the player's suspicion and removes them from the suspect list. |
| `/adp ledger verify` | Verify the hash chains. Tells you if anything has been tampered with. |

### Reading the suspects list

`/adp ledger suspects` output looks like this:

```
Current Suspects (3)
(material: +N = cumulative excess of that material across all violations)
  R4gnar95 - 19 violations (ENCHANTED_BOOK: +60)
  Arnold_158 - 8 violations (RAISER_ARMOR_TRIM_SMITHING_TEMPLATE: +50)
  facurolo - 7 violations (DIAMOND: +69)
Use /adp ledger stash <player> to see where they stashed items.
```

The number on the right of each line — `+60`, `+50`, `+69` — is the
**cumulative excess** of the named material across that suspect's violations.
Concretely:

- `R4gnar95 — 19 violations (ENCHANTED_BOOK: +60)` means the reconciliation
  engine has flagged R4gnar95 on 19 separate occasions, and across all of
  those events they were carrying a total of 60 more enchanted books than
  their ledger said they should hold.
- The material shown is the one with the highest cumulative excess, so it's
  the "what they're duping" at a glance.

### Finding the stash

Once you spot a suspect, run `/adp ledger stash <player>` to see where they put
the items. The output lists their last 20 stash events — items placed into
chests, shulkers, barrels, ender chests, lecterns, decorated pots,
horse/donkey/llama chests, chest boats and item frames — newest first:

```
Recent stashes by R4gnar95 (newest first, click coords to TP)
2026-05-31 14:22:05 16×ENCHANTED_BOOK → CHEST @ [overworld 102, 64, -200]
2026-05-31 14:21:48 8×DIAMOND_BLOCK → BARREL @ [overworld 102, 65, -200]
2026-05-31 14:15:11 1×ELYTRA → DECORATED_POT @ [the_end 0, 60, 0]
2026-05-31 14:10:33 32×NETHERITE_INGOT → ENDER_CHEST @ [overworld 50, 70, 12]
```

The bracketed coordinates are clickable in chat — a single click runs
`/execute in <world> run tp @s x y z`, teleporting you directly to the stash,
even across worlds. Hover for a confirmation tooltip.

## 8. Permissions

| Permission | Grants | Default |
|---|---|---|
| `antidupe.alerts` | Receives in-game dupe alerts (no command access) | op |
| `antidupe.ledger` | All `/adp ledger ...` commands | op |
| `antidupe.admin` | Includes both `antidupe.alerts` and `antidupe.ledger` | op |
| `antidupe.witness.exempt` | Holder is never counted as a nearby witness (e.g. vanished staff) | false |
| `antidupe.tag.view` | See the ownership tag in your own client even with `hide_tag_from_clients` on (applies on next login) | false |

If you're using a permissions plugin (LuckPerms, etc.), you can split duties:
give a moderator just `antidupe.alerts` to see dupe alerts without any command
access, or `antidupe.ledger` for the investigation commands. `antidupe.admin`
grants both at once.

## 9. Common scenarios

### "I want to test that it works."

1. Mine a diamond block legitimately.
2. Run `/adp ledger balance <your-name>`. You should see `DIAMOND_BLOCK: +1`.
3. Run `/adp ledger history <your-name>`. The latest entry should be
   `PICKUP +1 DIAMOND_BLOCK` with a `MINE` source attribution in the notes.
4. Run `/adp ledger verify`. The chain should report integrity verified across
   all entries.

### "A player thinks I unfairly took their item."

1. Run `/adp ledger reconcile <player>` while they're online. If their actual
   inventory matches their ledger balance, the items are legit and the
   reconciler reports no discrepancies.
2. If reconcile flags a surplus, run `/adp ledger history <player>` to see when
   the suspicious gains entered the ledger.
3. For the specific stash, `/adp ledger stash <player>` lists their recent
   container puts with clickable teleport coords.

### "I want to track a new item."

1. Open `materials.yml`.
2. Add the item's Material name to the `tracked_materials` list (e.g.
   `- TOTEM_OF_UNDYING`).
3. Optionally add it under `tmar_limits` with a per-minute cap and under
   `alert_thresholds` with a discrepancy ceiling.
4. Restart or reload the server.

### "I want to stop tracking shulkers."

You can't fully — shulker scanning is hardcoded because they're the primary
tool for shipping duped goods. You can remove `SHULKER_BOX` from
`tracked_materials` (which stops tagging the shulker box _item itself_ with
ownership), but their contents will still be scanned during reconciliation.

### "My network has multiple servers. How do I share tracking?"

Set `storage.backend: REDIS` on every server and point them at the same Redis
instance. Items keep their owner UUID via NBT as they travel through proxies
(BungeeCord / Velocity), and the shared ledger lets reconciliation on the
destination server see the original acquisition history. A dupe created on one
server is caught when its recipient is reconciled on another.

## 10. Troubleshooting

### "The plugin failed to start."

Check the console for the line beginning with
`Failed to initialize BetterAntiDupe:`. Common causes:

- **Redis is selected but the server can't reach it.** Either start your Redis
  server or switch `storage.backend` to `SQLITE`.
- **Wrong Java version.** The plugin requires Java 21+.
- **Old Minecraft version.** The plugin requires Paper API 1.21+.

### "I'm getting false-positive dupe alerts."

As of 3.3.0 the detection model is designed to make these rare. If you're still
seeing them:

- **Update to 3.3.2 first.** It fixed several causes of false alarms: worn
  armor/elytras counted twice, double chests not being tracked, shift-clicks
  into nearly-full chests, and an alert flood that happened when another plugin
  blocked an item pickup.
- **Turn sensitivity down.** `detection.sensitivity` (1–100, default 50) is
  the master dial. Lower it toward 1 for a more forgiving server; raise it
  toward 100 only if you want to catch the smallest discrepancies and accept
  more noise.
- **Clear the player.** Run `/adp ledger clear <player>` on a confirmed false
  positive. This resets their suspicion so they aren't re-flagged on small
  wobbles. Conversely, `/adp ledger confirm <player>` on a real duper makes
  future hits trip on far less.
- **Custom plugins / shops giving items.** Plugins that grant items via direct
  API (shop purchases, kit/reward plugins) bypass the events the ledger
  watches. The plugin self-heals this automatically — a balance that goes
  negative is recognised as a tracking gap and re-baselined to the player's
  real inventory rather than flagged. For zero-noise integration, plugin
  authors can call
  `ChainOfCustody.recordSystemGrant(player, material, amount, source)`.
- **Old items from before install.** No owner UUID, no ledger history. They
  become tracked the first time the player interacts with them; a never-seen
  player's inventory is baselined on first join. No manual action needed.

Note that acquisition-rate bursts (raid farms, fast vault looting) and solo
unwitnessed play no longer trigger alerts on their own — they only nudge a
player's suspicion, which decays on its own when nothing else is wrong.

### "I got a CRITICAL chunk-load dupe alert but the player swears they didn't cheat."

This alert fires when the same item entity is picked up twice. The usual cause
is a real dupe exploit, but there's one rare innocent case: if your server
crashed between the player picking the item up and the next chunk save, the
chunk reverts on restart and the player legitimately picks up the same entity
again. This produces a false-positive CRITICAL alert.

(Before 3.3.2 this alert could also fire hundreds of times in a row when
another plugin blocked a pickup — that was a bug, not a dupe, and it's fixed.
Each item can now only trigger this alert once.)

How to tell them apart:

- Check the console for a recent crash or abnormal shutdown around the alert
  timestamp. If yes, it's almost certainly the false positive.
- Use `/adp ledger reconcile <player>` to compare their actual inventory
  against ledger. A false positive will reconcile cleanly within a few
  minutes. A real dupe will keep showing excess.
- Clear the player's suspect status with `/adp ledger` once you're satisfied.

### "The chain integrity check failed."

If `/adp ledger verify` reports a broken chain, that means someone (or
something) modified the ledger database directly. Stop the server, back up the
database file, and investigate. The plugin won't refuse to run, but
reconciliation results from after the break point can't be trusted until you
decide what to do.

## 11. FAQ

### Does this affect server performance?

Tracking work happens off the main thread. The only main-thread work is reading
and writing item NBT, which is very cheap. On a busy server with hundreds of
tracked transactions per second, the plugin uses well under 1% of CPU.

### Does it work with mods like Geyser / ViaVersion?

Yes. The plugin operates entirely at the Paper API level, so any item that's a
real ItemStack in Paper is trackable.

### What happens to items that already existed before I installed it?

They have no owner UUID in their NBT and no ledger history. The plugin treats
them as legitimate (it fails open). The first time the holder picks one up or
moves it through a tracked event, it gets tagged with the owner UUID and joins
the ledger from that point onward — no manual action is needed.

### Can a sneaky admin tamper with the ledger?

Editing entries in the SQLite or Redis database directly will break the hash
chain, and `/adp ledger verify` will report exactly where the tampering started
and which entry was the last valid one.

### I used an older version. What changed?

BetterAntiDupe originally shipped two detection systems side by side: a
"Digital Isotope" system that wrote a unique signature into every tracked
item's NBT, and the Chain of Custody ledger. As of 3.0.0 the isotope system is
removed entirely. The reason is straightforward: per-item NBT broke vanilla
stacking — no two diamonds ever combined into a single slot — and the ledger
now catches everything the isotope system used to catch, plus several families
it never could (item frames, entity inventories, workstations, chunk-load
entity dupes, drop-pickup races, …).

Existing 2.x installs upgrading to 3.x: the plugin no longer reads the old
isotope data. The `isotopes` table in your SQLite file or the `iso:*` keys in
Redis are unused and can be deleted whenever you want to reclaim the space.

### Where do I report bugs?

Open an issue on the project's
[GitHub repository](https://github.com/ESMP-FUN/BetterAntiDupe/issues). Include
your server version, plugin version, the `storage.backend` you're using, and
the relevant console log lines (search for `BetterAntiDupe` or `[DUPE]`).

---

_Last updated for BetterAntiDupe 4.0.2 — Minecraft 1.21.x (Paper, Folia,
Spigot) and 26.x (Paper, on the `paper-26` branch)._
