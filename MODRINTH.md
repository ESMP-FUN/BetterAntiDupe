<img width="1000" alt="betterantidupebanner" src="https://github.com/user-attachments/assets/fe09ded7-0db9-44cb-90ad-eff69e9b6b64" />

**A forensic-grade item-duplication detector for Paper, Folia and Spigot servers.**

Most anti-cheat plugins focus on movement, reach and combat exploits. BetterAntiDupe
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
| **Minecraft versions** | 1.21.x (plain jar) · 26.x (`-mc26` jar) |
| **Java** | 21+ for 1.21.x · 25+ for 26.x |
| **External services** | None required (SQLite is bundled). Redis is optional for multi-server networks. |

---

## What it catches

A non-exhaustive list of dupe families BetterAntiDupe detects:

- **Rail / carpet / TNT / gravity dupers** — the classic piston and end-portal contraptions are *blocked outright*, not just detected (each toggleable in config)
- **Phantom-GUI container dupes** — open GUIs are force-closed when their shulker/chest is destroyed or their donkey/chest-boat unloads
- **Stack-clone exploits** — click-timing, cursor desync, drag-and-place tricks
- **Shulker / bundle laundering** — recursive content scan at any nesting depth
- **Item frame dupes** — piston-into-frame, chunk-race, end-crystal interaction variants
- **Entity inventory dupes** — horses, donkeys, llamas, chest boats, chest minecarts
- **Hopper laundering** — items passing through automation are scanned
- **Workstation outputs** — smithing, anvil, loom, stonecutter, cartography, grindstone, furnaces
- **Container transfers** — chests (single *and* double), barrels, ender chests, lecterns, decorated pots — recorded by what *actually* moved, so shift-clicks, number-key swaps, double-click gathering and drags are all measured exactly
- **Villager trades & enchanting** — buying a tracked item or enchanting a book is credited properly
- **Chunk-load entity respawn** — the "same item entity picked up twice" family
- **Drop-pickup race** — same-NBT dupes via item-entity persistence
- **Acquisition-rate abuse** — TMAR (Theoretical Max Acquisition Rate) thresholds per material
- **Witness-less acquisitions** — Proof of Witness flags players whose actions are never seen by others (vanished staff are correctly ignored, so invisible patrols can't skew trust)

Full coverage matrix and the rare edge cases are documented in the
[user guide](https://github.com/ESMP-FUN/BetterAntiDupe/blob/main/docs/user-guide.html).

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

**Invisible to players.** The ownership tag is stripped from the packets sent to
clients (on by default), so even players running NBT-viewer mods can't see it,
test it, or tell a tracked item from an untracked one — while the server-side
data stays fully intact for detection. The tag's very name is configurable, so
nothing in a leaked screenshot or stream frame reveals which plugin wrote it —
and renaming is safe: previously tagged items stay tracked and migrate to the
new name automatically. A strict mode can go further and strip *every* plugin's
custom item data from outbound packets, with a whitelist for the namespaces
your resource pack or client mods need. Staff with `antidupe.tag.view` keep
the tag visible in their own client.

Built to be **false-alarm shy**: actions are verified one tick after they happen
(so other plugins cancelling a pickup or block place can't skew the books),
items handed out by shop/kit/vault plugins are never held against the player,
and every alert is gated through per-material thresholds you control.

---

## Storage backends

Pick one, configurable in `config.yml`:

- **SQLite** *(default)* — file-based, persistent, zero ops. Perfect for single-server setups.
- **Redis** — fast and shareable across multiple servers behind a proxy.
- **Memory** — in-process only, lost on restart. Dev/testing only.

---

## Installation

1. Download the jar that matches your server: plain for Minecraft 1.21.x, `-mc26` for 26.x.
2. Drop it into `plugins/`.
3. Start the server. Defaults are sensible; the plugin generates `config.yml`
   and `materials.yml` on first launch.
4. Done. Run `/adp help` in-game to see admin commands.

---

## Commands

All commands live under `/antidupe` (aliases: `/adp`, `/betterantidupe`).

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

Permissions are split so staff roles can be scoped: `antidupe.alerts` receives
dupe alerts in chat (no command access), `antidupe.ledger` grants the commands
above, and `antidupe.admin` includes both. `antidupe.witness.exempt` keeps
invisibly-monitoring staff out of the witness pool, and `antidupe.tag.view`
lets a trusted admin see the (otherwise hidden) ownership tag in their own client.

---

## Configuration

Three YAML files in `plugins/BetterAntiDupe/`:

- `config.yml` — storage backend, modes (shadow / auto-delete), ledger settings
- `materials.yml` — tracked materials, rate limits, alert thresholds
- `messages.yml` — every in-game message; **fully translatable** (missing keys fall back to English)

**Speaks your language**: English, Português do Brasil, Español, Deutsch, Русский
and Polski are built in — one `language:` line in config.yml switches everything.

Alerts can also be pushed **outside the game**: Discord, Telegram, Slack, or any
custom JSON webhook (n8n, Zapier, your own bot) — with severity filtering and
burst protection built in. See the `notifications` section of `config.yml`.

Both are documented inline. Sensible defaults; you can add your own materials to
the list at any time and restart.

---

## 100% Free & Source Available

No license key, no telemetry, no "premium" gating. The source is on
[GitHub](https://github.com/ESMP-FUN/BetterAntiDupe). Issues and pull requests
are welcome.

---

## Links

- **Source / issues**: [github.com/ESMP-FUN/BetterAntiDupe](https://esmp-fun.gitbook.io/plugins/better-anti-dupe)
- **User guide**: [gitbook/user-guide](https://esmp-fun.gitbook.io/plugins/better-anti-dupe)
- **Notifications & translation guide**: [gitbook/notifications-and-translation](https://esmp-fun.gitbook.io/plugins/better-anti-dupe/notifications-and-translation)
