# AntiDupePro

**A forensic-grade item-duplication detector for Paper, Folia and Spigot servers.**

Most anti-cheat plugins focus on movement, reach and combat exploits. AntiDupePro
focuses on a category they usually ignore: the steady drip of duped items that
silently inflates your server's economy. It does this by writing an append-only,
tamper-evident ledger of every item movement and reconciling each player's
actual inventory against what the ledger says they should hold.

If something doesn't add up, you get an alert before the dupe spreads.

---

## Compatibility

| | |
|---|---|
| **Server software** | Paper, Folia, Spigot, and Paper-compatible forks (Purpur, Pufferfish, etc.) |
| **Minecraft versions** | 1.21.x (main branch) · 26.x (paper-26 branch) |
| **Java** | 21+ for 1.21.x · 25+ for 26.x |
| **External services** | None required (SQLite is bundled). Redis is optional for multi-server networks. |

---

## What it catches

A non-exhaustive list of dupe families AntiDupePro detects:

- **Stack-clone exploits** — click-timing, cursor desync, drag-and-place tricks
- **Shulker / bundle laundering** — recursive content scan at any nesting depth
- **Item frame dupes** — piston-into-frame, chunk-race, end-crystal interaction variants
- **Entity inventory dupes** — horses, donkeys, llamas, chest boats, chest minecarts
- **Hopper laundering** — items passing through automation are scanned
- **Workstation outputs** — smithing, anvil, loom, stonecutter, cartography, grindstone, furnaces
- **Container transfers** — chests, barrels, ender chests, lecterns, decorated pots
- **Chunk-load entity respawn** — the "same item entity picked up twice" family
- **Drop-pickup race** — same-NBT dupes via item-entity persistence
- **Acquisition-rate abuse** — TMAR (Theoretical Max Acquisition Rate) thresholds per material
- **Witness-less acquisitions** — Proof of Witness flags players whose actions are never seen by others

Full coverage matrix and the rare edge cases are documented in the
[user guide](https://github.com/darkstarworks/AntiDupePro/blob/main/docs/user-guide.html).

---

## How it works

Every tracked item carries the owner's UUID in NBT — items still stack vanilla-style.
Every gain and loss event (mine, craft, pickup, container put/take, frame put/take,
workstation output, etc.) is recorded as a SHA-256-linked ledger entry. The chain
is tamper-evident: editing the database directly breaks the hash chain and
`/adp ledger verify` reports exactly where.

Reconciliation walks the player's inventory recursively — including the contents
of held shulkers and bundles — and compares the total to the ledger balance.
A surplus is a dupe.

---

## Storage backends

Pick one, configurable in `config.yml`:

- **SQLite** *(default)* — file-based, persistent, zero ops. Perfect for single-server setups.
- **Redis** — fast and shareable across multiple servers behind a proxy.
- **Memory** — in-process only, lost on restart. Dev/testing only.

---

## Installation

1. Download the jar.
2. Drop it into `plugins/`.
3. Start the server. Defaults are sensible; the plugin generates `config.yml`
   and `materials.yml` on first launch.
4. Done. Run `/adp help` in-game to see admin commands.

---

## Commands

All commands live under `/adp` (aliases: `/antidupe`, `/antidupepro`).

| Command | What it does |
|---|---|
| `/adp ledger status` | Chain tip, current suspects, system health |
| `/adp ledger balance <player>` | Expected balances for each tracked material |
| `/adp ledger history <player>` | Recent ledger entries |
| `/adp ledger witness <player>` | Witness statistics and suspicion analysis |
| `/adp ledger suspects` | List all currently flagged players |
| `/adp ledger reconcile <player>` | Force a balance check on an online player |
| `/adp ledger trust <player>` | Show accumulated trust score |
| `/adp ledger verify` | Verify the entire hash chain |

Permission `antidupe.admin` grants all of the above and routes dupe alerts to chat.

---

## Configuration

Two YAML files in `plugins/AntiDupePro/`:

- `config.yml` — storage backend, modes (shadow / auto-delete), ledger settings
- `materials.yml` — tracked materials, rate limits, alert thresholds

Both are documented inline. Sensible defaults; you can add your own materials to
the list at any time and restart.

---

## Free and open source

No license key, no telemetry, no "premium" gating. The source is on
[GitHub](https://github.com/darkstarworks/AntiDupePro). Issues and pull requests
are welcome.

---

## Links

- **Source / issues**: [github.com/darkstarworks/AntiDupePro](https://github.com/darkstarworks/AntiDupePro)
- **User guide**: [docs/user-guide.html](https://github.com/darkstarworks/AntiDupePro/blob/main/docs/user-guide.html)
- **Changelog**: [CHANGELOG.md](https://github.com/darkstarworks/AntiDupePro/blob/main/CHANGELOG.md)
