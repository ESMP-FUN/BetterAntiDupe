[CENTER][IMG]https://github.com/ESMP-FUN/BetterAntiDupe/blob/master/images/betterantidupebanner.png?raw=true[/IMG]
 
[SIZE=4][COLOR=#7f8c8d]Forensic-grade item-duplication detector[/COLOR][/SIZE]
[SIZE=3]Paper · Folia · Spigot — 1.21.x and 26.x[/SIZE]
[SIZE=4][I]Most anti-cheat plugins watch movement and combat. BetterAntiDupe[/I]
[I]watches the items themselves — and catches the dupes that quietly inflate your[/I]
[I]economy until your spawn is full of free elytras.[/I][/SIZE]
[SIZE=3][COLOR=#808080]
——————————————————————————————
[/COLOR][/SIZE][/CENTER]
[SIZE=6][COLOR=#0000ff][B]Why Better Anti Dupe?[/B][/COLOR][/SIZE]
Other anti-dupe plugins use per-item NBT tags — every diamond gets a unique ID,
which breaks vanilla stacking and irritates your players. BetterAntiDupe takes
a different approach: items keep their owner UUID and stack normally, but every
gain and loss is recorded in a tamper-evident ledger. When a player's actual
inventory diverges from what the ledger says, you get an alert.
The result: comprehensive detection without the UX friction.
[CENTER][SIZE=3][COLOR=#808080]
——————————————————————————————
[/COLOR][/SIZE][/CENTER]
[SIZE=6][COLOR=#0000ff][B]What it catches[/B][/COLOR][/SIZE]
A partial list of dupe families BetterAntiDupe detects out of the box:
[LIST]
[*][B]Rail / carpet / TNT / gravity dupers[/B] — the classic piston and end-portal contraptions are [I]blocked outright[/I], not just detected (each toggleable)
[*][B]Phantom-GUI container dupes[/B] — open GUIs force-closed when their shulker/chest is destroyed or their donkey/chest-boat unloads
[*][B]Restart dupers[/B] — items moved during a shutdown can be saved twice; all inventories are closed before the save, so nothing is in flight
[*][B]Stack-clone exploits[/B] — click-timing, drag, cursor desync
[*][B]Shulker / bundle laundering[/B] — recursive content scan up to depth 10
[*][B]Item frame dupes[/B] — piston-into-frame, chunk-race, end-crystal variants
[*][B]Entity inventory dupes[/B] — horse, donkey, llama, chest boat, chest minecart
[*][B]Hopper laundering[/B] — items moving through automation are scanned
[*][B]Workstation outputs[/B] — smithing, anvil, loom, stonecutter, cartography, grindstone
[*][B]Furnace / lectern / decorated pot / ender chest[/B] — full transfer tracking
[*][B]Single & double chests[/B] — transfers recorded by what [I]actually[/I] moved (shift-clicks, number keys, double-click gathering, drags)
[*][B]Villager trades & book enchanting[/B] — tracked items bought or created are credited properly
[*][B]Chunk-load entity respawn[/B] — same item entity picked up twice
[*][B]Drop-pickup race[/B] — same-NBT dupes via entity persistence
[*][B]Acquisition-rate abuse[/B] — TMAR thresholds per material
[*][B]Witness-less acquisitions[/B] — Proof of Witness flags suspicious solo patterns
[/LIST]
The full coverage matrix is in the [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe']user guide[/URL].
[CENTER][SIZE=3][COLOR=#808080]
——————————————————————————————
[/COLOR][/SIZE][/CENTER]
[SIZE=6][COLOR=#0000ff][B]Compatibility[/B][/COLOR][/SIZE]
[LIST]
[*][B]Server software:[/B] Paper, Folia, Spigot, and Paper-compatible forks
[*][B]Minecraft:[/B] 1.21.x on the main branch, 26.x on the [I]paper-26[/I] branch
[*][B]Java:[/B] 21+ for 1.21.x · 25+ for 26.x
[*][B]External services:[/B] none required (SQLite bundled). Redis optional for proxy networks.
[/LIST]
Folia compatibility is first-class — the plugin detects Folia at runtime and
dispatches scheduler calls to the regional schedulers automatically.
[CENTER][SIZE=3][COLOR=#808080]
——————————————————————————————
[/COLOR][/SIZE][/CENTER]
[SIZE=6][COLOR=#0000ff][B]Storage backends[/B][/COLOR][/SIZE]
Pick one in [I][ICODE]config.yml[/ICODE][/I]:
[LIST]
[*][B]SQLite[/B] — file-based, persistent, zero ops. Default and recommended for single servers.
[*][B]Redis[/B] — fast and shareable across multiple servers behind Velocity / BungeeCord.
[*][B]Memory[/B] — in-process only, lost on restart. For testing.
[/LIST]
[CENTER][SIZE=3][COLOR=#808080]
——————————————————————————————
[/COLOR][/SIZE][/CENTER]
[SIZE=6][COLOR=#0000ff][B]Installation[/B][/COLOR][/SIZE]
[LIST=1]
[*]Download the jar
[*]Drop it into [I][ICODE]plugins/[/ICODE][/I]
[*]Start the server
[*]Done — the plugin generates [I][ICODE]config.yml[/ICODE][/I] and [I][ICODE]materials.yml[/ICODE][/I] with sensible defaults
[/LIST]
Run [I][ICODE]/adp help[/ICODE][/I] in-game to see the admin commands.
[CENTER][SIZE=3][COLOR=#808080]
——————————————————————————————
[/COLOR][/SIZE][/CENTER]
[SIZE=6][COLOR=#0000ff][B]Commands[/B][/COLOR][/SIZE]
All commands live under [I][ICODE]/antidupe[/ICODE][/I] (aliases: [I][ICODE]/adp[/ICODE][/I], [I][ICODE]/betterantidupe[/ICODE][/I]).
[LIST]
[*][I][ICODE]/adp ledger status[/ICODE][/I] — chain tip, current suspects, system health
[*][I][ICODE]/adp ledger balance <player>[/ICODE][/I] — expected balances for each material
[*][I][ICODE]/adp ledger history <player>[/ICODE][/I] — recent ledger entries
[*][I][ICODE]/adp ledger witness <player>[/ICODE][/I] — witness statistics and suspicion analysis
[*][I][ICODE]/adp ledger suspects[/ICODE][/I] — list all currently flagged players
[*][I][ICODE]/adp ledger reconcile <player>[/ICODE][/I] — force a balance check on an online player
[*][I][ICODE]/adp ledger trust <player>[/ICODE][/I] — accumulated trust score
[*][I][ICODE]/adp ledger verify[/ICODE][/I] — verify the entire hash chain
[/LIST]
Permission [I][ICODE]antidupe.admin[/ICODE][/I] grants all of the above and routes dupe alerts to chat.
[CENTER][SIZE=3][COLOR=#808080]
——————————————————————————————
[/COLOR][/SIZE][/CENTER]
[SIZE=6][COLOR=#0000ff][B]Configuration[/B][/COLOR][/SIZE]
Two files in [I][ICODE]plugins/BetterAntiDupe/[/ICODE][/I]:
[LIST]
[*][B][ICODE]config.yml[/ICODE][/B] — storage backend, modes (shadow / auto-delete), ledger settings
[*][B][ICODE]materials.yml[/ICODE][/B] — tracked materials, rate limits, alert thresholds
[*][B][ICODE]messages.yml[/ICODE][/B] — every in-game message, fully translatable (deleted keys fall back to English)
[/LIST]

[B]Speaks your language[/B]: English, Português do Brasil, Español, Deutsch, Русский and Polski are built in — one [I]language:[/I] line in config.yml switches everything.

Dupe alerts can also be pushed [B]outside the game[/B]: Discord, Telegram, Slack, or any custom JSON webhook — with severity filtering and burst protection. See the [I]notifications[/I] section of [ICODE]config.yml[/ICODE].
Both files are extensively commented. You can add or remove tracked materials at
any time without restarting the dependency stack — just edit the file and reload.
[CENTER][SIZE=3][COLOR=#808080]
——————————————————————————————
[/COLOR][/SIZE][/CENTER]
[SIZE=6][COLOR=#0000ff][B]100% Free & Source Available[/B][/COLOR][/SIZE]
[LIST]
[*]No license key
[*]No "premium" feature gating
[*]Full source on GitHub, issues and PRs welcome
[/LIST]
[SIZE=5][COLOR=#0000ff][B]Anonymous metrics[/B][/COLOR][/SIZE]
Since 4.2.0 the plugin reports anonymous usage statistics. Here's why, plainly:
BetterAntiDupe works quietly and the docs are thorough, so almost nobody opens a
ticket — which leaves no way to know which Minecraft versions are actually running
it. Knowing that is what makes it possible to fight duplication exploits for those
versions [I]first[/I], instead of guessing.
[LIST]
[*][B]Sent[/B] — storage backend, which prevention toggles are on, tracked-material count, language, shadow mode / auto-delete / tag hiding state, plus server software, Minecraft version, Java version and plugin version
[*][B]Never sent[/B] — IP addresses, server names, player names or UUIDs, item data, ledger contents
[*][B]Kept private[/B] — not published on a public page. While the install base is small, public numbers would tell dupers how likely any given server is to be protected
[*][B]Off with one line[/B] — [ICODE]metrics.enabled: false[/ICODE] in config.yml sends nothing at all
[*][B]Error reporting[/B] — separate toggle, off by default
[/LIST]
[CENTER][SIZE=3][COLOR=#808080]
——————————————————————————————
[/COLOR][/SIZE][/CENTER]
[SIZE=6][COLOR=#0000ff][B]Links[/B][/COLOR][/SIZE]
[LIST]
[*][URL='https://github.com/ESMP-FUN/BetterAntiDupe']Source code and issue tracker[/URL]
[*][URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe']User guide[/URL]
[*][URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/notifications-and-translation']Notifications & translation guide[/URL]
[*][URL='https://github.com/ESMP-FUN/BetterAntiDupe/blob/master/CHANGELOG.md']Changelog[/URL]
[/LIST]
[CENTER][SIZE=3][I]If BetterAntiDupe saved you from cleaning up a dupe wave, a positive review[/I]
[I]on this page is the best way to support development.[/I][/SIZE][/CENTER]