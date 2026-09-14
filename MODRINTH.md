<img width="1000" alt="betterantidupebanner" src="https://github.com/user-attachments/assets/fe09ded7-0db9-44cb-90ad-eff69e9b6b64" />

**Stops item duplication on your Minecraft server, and tells you who tried.**

Most anti-cheat plugins watch movement and combat. BetterAntiDupe watches the items themselves. It keeps a record of every valuable item a player gains and loses, and when someone is carrying more than their record can explain, you get an alert, a list of where they put the items, and a click to teleport there.

Install it and you are protected. The defaults are sensible, and nothing is ever taken from a player until you decide it should be.

---

## Will it work on my server?

| | |
|---|---|
| **Server software** | Paper, Folia, Spigot, or a Paper fork like Purpur |
| **Minecraft** | 1.21.x with the plain download, 26.x with the `-mc26` download |
| **Java** | 21 or newer for 1.21.x, 25 or newer for 26.x |
| **Anything else** | Nothing. Redis is only needed if several servers share records. |

---

## What it blocks

The classic dupe machines are simply not allowed to work, so the extra item never exists. Each one has its own switch.

- **Rail and carpet dupers**
- **TNT dupers**
- **Sand and gravel portal dupers**
- **Ghost chest windows**: a window closes when its chest, shulker box, donkey or chest boat disappears
- **Restart dupes**: every open window closes the moment the server starts shutting down

## What it catches

Everything else is caught by noticing a player holds more than they could have:

- **Carrying more than they earned**, counted inside shulker boxes and bundles too
- **The same dropped item picked up twice**
- **More drops than a block or item frame gave**
- **Copies made inside a chest**, by counting each player's stored items and alerting when more come out than went in
- **Items washed through hoppers**, with the route written into the item's history (or hoppers blocked from moving tracked items at all)
- **Gaining things impossibly fast**, with a limit you set per item
- **Nobody ever seeing them get anything**, on a busy server
- **Someone editing the records** by hand, even an admin

Crafting, anvils, smithing tables, furnaces, villager trades, enchanting, chests, barrels, ender chests, item frames and animal chests are all counted by what actually moved.

---

## Made for staff, not just for detection

- **Find the stash.** `/adp ledger stash <player>` lists where they put tracked items. Click the coordinates to teleport there, even in another world.
- **Decide in five minutes.** Every alert has **[History]** and **[Stash]** buttons. Then `confirm`, `clear`, or take the extras back with one click.
- **Alerts on your phone.** Discord, Telegram, Slack, or your own webhook, with only the serious ones sent and repeats held back. `/adp test alert` checks your setup in seconds.
- **Watch first, act later.** Out of the box it only alerts. Turn on automatic removal when you trust it, and it takes back only the extra, never a whole stack.
- **Items stack normally.** Players notice nothing.
- **Hidden from players.** The plugin's mark on items is kept out of what players' games receive, so mods cannot see it.
- **Speaks your language.** English, Português do Brasil, Español, Deutsch, Русский and Polski are built in.
- **Several servers?** Share records through Redis, so an item duped on one server is spotted on another.

---

## Installation

1. Download the jar for your Minecraft version: plain for 1.21.x, `-mc26` for 26.x.
2. Drop it into `plugins/` and restart.
3. That's it. [Testing in-game](https://esmp-fun.gitbook.io/plugins/better-anti-dupe/help/testing-in-game) shows you it working.

---

## Commands

Every command starts with `/adp`.

| Command | What it does |
|---|---|
| `/adp ledger suspects` | Everyone currently suspected, worst first |
| `/adp ledger reconcile <player>` | Check what an online player carries right now |
| `/adp ledger history <player>` | Their last 15 records |
| `/adp ledger stash <player>` | Where they put tracked items, with click-to-teleport |
| `/adp ledger remove <player>` | Take back only what they carry extra, after you confirm |
| `/adp ledger confirm <player>` | Mark a real duper, and run your punishment command if you set one |
| `/adp ledger clear <player>` | Mark a false alarm |
| `/adp ledger verify` | Check nobody has edited the records |

`antidupe.alerts` sees alerts only, `antidupe.ledger` uses the commands, and `antidupe.admin` gets both. The [full list](https://esmp-fun.gitbook.io/plugins/better-anti-dupe/reference/commands-and-permissions) is in the guide.

---

## Free and source available

No licence key and nothing locked behind a premium version. The source is on [GitHub](https://github.com/ESMP-FUN/BetterAntiDupe), and issues and pull requests are welcome.

### Anonymous statistics

The plugin works quietly, so almost nobody opens a ticket. That leaves no way to know which Minecraft versions it actually runs on, and knowing that is what makes it possible to fight dupes on those versions first.

- **Sent:** which storage you use, which switches are on, how many items you track, your language, your server software and versions, and plain counts of how many dupes were caught, removed and blocked, by item and type.
- **Never sent:** addresses, server names, player names, item data, or anything from your records.
- **Kept private.** While few servers run this, public numbers would tell dupers how likely a server is to be protected.
- **Error reports** send what went wrong when the plugin errors, with anything resembling a password, token or id removed first.

Set `metrics.enabled: false` to send nothing at all, or `metrics.error_reporting: false` to keep error details to yourself.

---

## Links

- **Guide**: [esmp-fun.gitbook.io/plugins/better-anti-dupe](https://esmp-fun.gitbook.io/plugins/better-anti-dupe)
- **When an alert comes in**: [step by step](https://esmp-fun.gitbook.io/plugins/better-anti-dupe/using-it/when-an-alert-comes-in)
- **Troubleshooting**: [common problems](https://esmp-fun.gitbook.io/plugins/better-anti-dupe/help/troubleshooting)
- **Source and issues**: [github.com/ESMP-FUN/BetterAntiDupe](https://github.com/ESMP-FUN/BetterAntiDupe)
- **Changelog**: [CHANGELOG.md](https://github.com/ESMP-FUN/BetterAntiDupe/blob/master/CHANGELOG.md)
