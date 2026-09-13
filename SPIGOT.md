[CENTER][IMG]https://github.com/ESMP-FUN/BetterAntiDupe/blob/master/images/betterantidupebanner.png?raw=true[/IMG]

[SIZE=4][COLOR=#7f8c8d]Stops item duplication, and tells you who tried[/COLOR][/SIZE]
[SIZE=3]Paper, Folia, Spigot - 1.21.x and 26.x[/SIZE]
[SIZE=4][I]Most anti-cheat plugins watch movement and combat. BetterAntiDupe[/I]
[I]watches the items themselves, and catches the dupes that quietly fill[/I]
[I]your spawn with free elytras.[/I][/SIZE]
[SIZE=3][COLOR=#808080]
------------------------------
[/COLOR][/SIZE][/CENTER]
[SIZE=6][COLOR=#0000ff][B]Why Better Anti Dupe?[/B][/COLOR][/SIZE]
It keeps a record of every valuable item a player gains and loses. When someone is carrying more than their record can explain, you get an alert, a list of where they put the items, and a click to teleport there.

Items stack normally, players notice nothing, and nothing is ever taken from anyone until you decide it should be. Install it and you are protected.
[CENTER][SIZE=3][COLOR=#808080]
------------------------------
[/COLOR][/SIZE][/CENTER]
[SIZE=6][COLOR=#0000ff][B]What it blocks[/B][/COLOR][/SIZE]
The classic dupe machines are simply not allowed to work, so the extra item never exists. Each has its own switch.
[LIST]
[*][B]Rail and carpet dupers[/B]
[*][B]TNT dupers[/B]
[*][B]Sand and gravel portal dupers[/B]
[*][B]Ghost chest windows[/B] - a window closes when its chest, shulker box, donkey or chest boat disappears
[*][B]Restart dupes[/B] - every open window closes the moment the server starts shutting down
[/LIST]
[SIZE=6][COLOR=#0000ff][B]What it catches[/B][/COLOR][/SIZE]
Everything else is caught by noticing a player holds more than they could have:
[LIST]
[*][B]Carrying more than they earned[/B], counted inside shulker boxes and bundles too
[*][B]The same dropped item picked up twice[/B]
[*][B]More drops than a block or item frame gave[/B]
[*][B]Items washed through hoppers[/B], with the route written into the item's history (or hoppers blocked from moving tracked items at all)
[*][B]Gaining things impossibly fast[/B], with a limit you set per item
[*][B]Nobody ever seeing them get anything[/B], on a busy server
[*][B]Someone editing the records[/B] by hand, even an admin
[/LIST]
Crafting, anvils, smithing tables, furnaces, villager trades, enchanting, chests, barrels, ender chests, item frames and animal chests are all counted by what actually moved.
[CENTER][SIZE=3][COLOR=#808080]
------------------------------
[/COLOR][/SIZE][/CENTER]
[SIZE=6][COLOR=#0000ff][B]Made for staff[/B][/COLOR][/SIZE]
[LIST]
[*][B]Find the stash.[/B] [ICODE]/adp ledger stash <player>[/ICODE] lists where they put tracked items. Click the coordinates to teleport there, even in another world.
[*][B]Decide in five minutes.[/B] Every alert has [B][History][/B] and [B][Stash][/B] buttons. Then confirm, clear, or take the extras back with one click.
[*][B]Alerts on your phone.[/B] Discord, Telegram, Slack, or your own webhook, with only the serious ones sent and repeats held back. [ICODE]/adp test alert[/ICODE] checks your setup in seconds.
[*][B]Watch first, act later.[/B] Out of the box it only alerts. Turn on automatic removal when you trust it, and it takes back only the extra, never a whole stack.
[*][B]Hidden from players.[/B] The plugin's mark on items is kept out of what players' games receive, so mods cannot see it.
[*][B]Speaks your language.[/B] English, Português do Brasil, Español, Deutsch, Русский and Polski are built in.
[*][B]Several servers?[/B] Share records through Redis, so an item duped on one server is spotted on another.
[/LIST]
[CENTER][SIZE=3][COLOR=#808080]
------------------------------
[/COLOR][/SIZE][/CENTER]
[SIZE=6][COLOR=#0000ff][B]Will it work on my server?[/B][/COLOR][/SIZE]
[LIST]
[*][B]Server software:[/B] Paper, Folia, Spigot, or a Paper fork like Purpur
[*][B]Minecraft:[/B] 1.21.x with the plain download, 26.x with the [ICODE]-mc26[/ICODE] download
[*][B]Java:[/B] 21 or newer for 1.21.x, 25 or newer for 26.x
[*][B]Anything else:[/B] nothing. Redis is only needed if several servers share records.
[/LIST]
[SIZE=6][COLOR=#0000ff][B]Installation[/B][/COLOR][/SIZE]
[LIST=1]
[*]Download the jar for your Minecraft version
[*]Drop it into [ICODE]plugins/[/ICODE] and restart
[*]That's it. [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/help/testing-in-game']Testing in-game[/URL] shows you it working.
[/LIST]
[CENTER][SIZE=3][COLOR=#808080]
------------------------------
[/COLOR][/SIZE][/CENTER]
[SIZE=6][COLOR=#0000ff][B]Commands[/B][/COLOR][/SIZE]
Every command starts with [ICODE]/adp[/ICODE].
[LIST]
[*][ICODE]/adp ledger suspects[/ICODE] - everyone currently suspected, worst first
[*][ICODE]/adp ledger reconcile <player>[/ICODE] - check what an online player carries right now
[*][ICODE]/adp ledger history <player>[/ICODE] - their last 15 records
[*][ICODE]/adp ledger stash <player>[/ICODE] - where they put tracked items, with click-to-teleport
[*][ICODE]/adp ledger remove <player>[/ICODE] - take back only what they carry extra, after you confirm
[*][ICODE]/adp ledger confirm <player>[/ICODE] - mark a real duper, and run your punishment command if you set one
[*][ICODE]/adp ledger clear <player>[/ICODE] - mark a false alarm
[*][ICODE]/adp ledger verify[/ICODE] - check nobody has edited the records
[/LIST]
[ICODE]antidupe.alerts[/ICODE] sees alerts only, [ICODE]antidupe.ledger[/ICODE] uses the commands, and [ICODE]antidupe.admin[/ICODE] gets both. The [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/reference/commands-and-permissions']full list[/URL] is in the guide.
[CENTER][SIZE=3][COLOR=#808080]
------------------------------
[/COLOR][/SIZE][/CENTER]
[SIZE=6][COLOR=#0000ff][B]Free and source available[/B][/COLOR][/SIZE]
[LIST]
[*]No licence key
[*]Nothing locked behind a premium version
[*]Full source on GitHub, issues and pull requests welcome
[/LIST]
[SIZE=5][COLOR=#0000ff][B]Anonymous statistics[/B][/COLOR][/SIZE]
The plugin works quietly, so almost nobody opens a ticket. That leaves no way to know which Minecraft versions it actually runs on, and knowing that is what makes it possible to fight dupes on those versions first.
[LIST]
[*][B]Sent[/B] - which storage you use, which switches are on, how many items you track, your language, your server software and versions, and plain counts of how many dupes were caught, removed and blocked, by item and type
[*][B]Never sent[/B] - addresses, server names, player names, item data, or anything from your records
[*][B]Kept private[/B] - while few servers run this, public numbers would tell dupers how likely a server is to be protected
[*][B]Error reports[/B] - what went wrong when the plugin errors, with anything resembling a password, token or id removed first
[/LIST]
Set [ICODE]metrics.enabled: false[/ICODE] to send nothing at all, or [ICODE]metrics.error_reporting: false[/ICODE] to keep error details to yourself.
[CENTER][SIZE=3][COLOR=#808080]
------------------------------
[/COLOR][/SIZE][/CENTER]
[SIZE=6][COLOR=#0000ff][B]Links[/B][/COLOR][/SIZE]
[LIST]
[*][URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe']Guide[/URL]
[*][URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/using-it/when-an-alert-comes-in']When an alert comes in[/URL]
[*][URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/help/troubleshooting']Troubleshooting[/URL]
[*][URL='https://github.com/ESMP-FUN/BetterAntiDupe']Source code and issue tracker[/URL]
[*][URL='https://github.com/ESMP-FUN/BetterAntiDupe/blob/master/CHANGELOG.md']Changelog[/URL]
[/LIST]
[CENTER][SIZE=3][I]If BetterAntiDupe saved you from cleaning up a dupe wave, a positive review[/I]
[I]on this page is the best way to support development.[/I][/SIZE][/CENTER]
