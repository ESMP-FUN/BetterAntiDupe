[CENTER][IMG]https://raw.githubusercontent.com/ESMP-FUN/BetterAntiDupe/refs/heads/master/brand/bad-banner-1280x420.png[/IMG]

[SIZE=5][B]Stops item duplication on your Minecraft server, and tells you who tried.[/B][/SIZE]

Better Anti-Dupe watches the items themselves.
It keeps a record of every valuable item a player gains and loses.

When something's up, you are notified with a list of where
they put the items, and a [B][click to teleport][/B] there.

Install it and you are protected.
The defaults are sensible, and nothing is ever taken from a player until you decide it should be.
[SIZE=3][COLOR=#808080]
------------------------------
[/COLOR][/SIZE][/CENTER]
[SIZE=6][COLOR=#0000ff][B]Will it work on my server?[/B][/COLOR][/SIZE]
[LIST]
[*][B]Server software:[/B] Paper, Folia, Spigot, or a Paper fork
[*][B]Minecraft:[/B] 1.21.x and 26.x, all with the same download
[*][B]Java:[/B] 21+ for 1.21.x or 25+ for 26.x
[*][B]Anything else:[/B] Nothing. Redis is optional.
[/LIST]
[SPOILER="Which download do I need?"]
There is only one. The same jar works on every supported Minecraft version and picks the right support for your server when it starts.

More info? [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/getting-started/install']Read about it in the docs[/URL]
[/SPOILER]
[CENTER][SIZE=3][COLOR=#808080]
------------------------------
[/COLOR][/SIZE][/CENTER]
[SIZE=6][COLOR=#0000ff][B]What it blocks[/B][/COLOR][/SIZE]
The classic dupe machines stop working the moment you install the plugin.
Each one has its own switch, in case you want to allow it.

[SPOILER="Rail and carpet dupers"]
One of the oldest dupes in the game.
All it needs is a piston and a rail or carpet.
With Better Anti-Dupe, these dupe machines are disabled by default.

More info? [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/using-it/what-it-blocks-and-catches']Read about it in the docs[/URL]
[/SPOILER]
[SPOILER="TNT dupers"]
TNT dupers power world eaters and flying machines.
They stop working from day one.
Running an anarchy or tech server? One switch turns them back on.

More info? [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/using-it/what-it-blocks-and-catches']Read about it in the docs[/URL]
[/SPOILER]
[SPOILER="Sand and gravel portal dupers"]
Sand and gravel can no longer be copied through a portal.
Farms and flying machines that push sand keep working.

More info? [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/using-it/what-it-blocks-and-catches']Read about it in the docs[/URL]
[/SPOILER]
[SPOILER="Ghost chest windows"]
A player can't keep taking items from a chest that's already gone.
The window simply closes.
Nobody playing normally will ever notice.

More info? [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/using-it/what-it-blocks-and-catches']Read about it in the docs[/URL]
[/SPOILER]
[SPOILER="Hidden menu dupes"]
Most dupes in other plugins (backpacks, ender chests, storage menus) start the same way: a cheat client hides the menu on screen without telling the server it closed.
Mining, building, hitting, dropping, swapping hands, switching slots, or running a command while a menu is still open is stopped, the menu closes, and staff get an alert.
Actions in the first second after a menu opens are always allowed so lag never trips it.

More info? [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/using-it/what-it-blocks-and-catches']Read about it in the docs[/URL]
[/SPOILER]
[SPOILER="Chest copies from blocked placements"]
When a land-claim or protection plugin blocks a placement that already spilled a container (such as the last eye of ender over a chest in an end portal), the container comes back while a copy drops on the ground.
Better Anti-Dupe puts everything back and clears the copy, so the container keeps its items and nothing is doubled.

More info? [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/using-it/what-it-blocks-and-catches']Read about it in the docs[/URL]
[/SPOILER]
[SPOILER="Restart dupes"]
No machine needed, just good timing during a restart.
Every open window closes as the server shuts down.
Nothing is left to copy.

More info? [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/using-it/what-it-blocks-and-catches']Read about it in the docs[/URL]
[/SPOILER]

[SIZE=6][COLOR=#0000ff][B]What it catches[/B][/COLOR][/SIZE]
Everything else. It notices when a player holds more than possible.

[SPOILER="Carrying more than they earned"]
The heart of the plugin.
It knows what each player earned, and counts what they hold.
Shulker boxes, bundles, and sulfur cube buckets included. Holding more? You'll know.

More info? [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/using-it/what-it-blocks-and-catches']Read about it in the docs[/URL]
[/SPOILER]
[SPOILER="Faked owner marks"]
Every mark the plugin puts on an item is signed with a secret only your server knows.
Marks made by hand in creative mode, with an item editor, or by command do not verify, are ignored so nobody's record is touched, and raise an alert to staff.

More info? [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/using-it/what-it-blocks-and-catches']Read about it in the docs[/URL]
[/SPOILER]
[SPOILER="The same dropped item picked up twice"]
Every dropped item is one of a kind.
If the same one is picked up twice, it was copied.
This is the most certain alert there is.

More info? [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/using-it/what-it-blocks-and-catches']Read about it in the docs[/URL]
[/SPOILER]
[SPOILER="More drops than a block or item frame gave"]
Breaking a block or a frame drops a known amount.
Picking up more than that nearby means something was copied.

More info? [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/using-it/what-it-blocks-and-catches']Read about it in the docs[/URL]
[/SPOILER]
[SPOILER="Small dupes hidden by storing them away"]
Dupe a few items, hide them in a chest, and hope nobody notices.
It's an old trick.
Small amounts are remembered for a day, and they add up to an alert.

More info? [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/using-it/what-it-blocks-and-catches']Read about it in the docs[/URL]
[/SPOILER]
[SPOILER="Copies made inside a chest"]
Some dupes never touch a player's inventory.
The copies simply appear in a chest.
You're told whose items they were, who took them, and where.

More info? [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/using-it/what-it-blocks-and-catches']Read about it in the docs[/URL]
[/SPOILER]
[SPOILER="Items washed through hoppers"]
Dupers pass items through hoppers to make them look clean.
The route is written down, so you can follow it.
Or block hoppers from moving watched items at all.

More info? [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/using-it/what-it-blocks-and-catches']Read about it in the docs[/URL]
[/SPOILER]
[SPOILER="Gaining things impossibly fast"]
You decide how many of an item anyone could honestly get in a minute.
Going past it makes a player worth a closer look.

More info? [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/using-it/what-gets-tracked']Read about it in the docs[/URL]
[/SPOILER]
[SPOILER="Nobody ever seeing them get anything"]
Duping usually happens alone.
A player whose gains nobody ever sees becomes a little more suspicious.
It never accuses anyone on its own.

More info? [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/using-it/what-it-blocks-and-catches']Read about it in the docs[/URL]
[/SPOILER]
[SPOILER="Someone editing the records"]
Every record is sealed to the one before it.
Even an admin can't quietly change history.
One command shows exactly where it was touched.

More info? [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/using-it/what-it-blocks-and-catches']Read about it in the docs[/URL]
[/SPOILER]

Crafting, anvils, smithing tables, stonecutters, looms, cartography tables, grindstones, furnaces, villager trades, enchanting, chests, barrels, ender chests, shelves, decorated pots, lecterns, item frames, armor stands, animal chests, and sulfur cubes (26.2+). Everything is monitored.
[CENTER][SIZE=3][COLOR=#808080]
------------------------------
[/COLOR][/SIZE][/CENTER]
[SIZE=6][COLOR=#0000ff][B]Made for staff, not just for detection[/B][/COLOR][/SIZE]
[CENTER]
[IMG]https://raw.githubusercontent.com/ESMP-FUN/BetterAntiDupe/refs/heads/master/docs/.gitbook/assets/tour-watch-only.png[/IMG]
[IMG]https://raw.githubusercontent.com/ESMP-FUN/BetterAntiDupe/refs/heads/master/docs/.gitbook/assets/alert-chat.png[/IMG]
[/CENTER]
[SPOILER="Guided in-game setup (/adp setup)"]
Run [ICODE]/adp setup[/ICODE] in game to walk through the main settings one question at a time, starting with your language.
On modern Paper servers it opens native Dialog screens; on Spigot and older builds it uses clickable chat buttons.
Every answer saves to [ICODE]config.yml[/ICODE] and starts working straight away.
[IMG]https://raw.githubusercontent.com/ESMP-FUN/BetterAntiDupe/refs/heads/master/docs/.gitbook/assets/tour-watch-only.png[/IMG]

More info? [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/getting-started/setup-tour']Read about it in the docs[/URL]
[/SPOILER]
[SPOILER="Find the stash"]
Knowing someone duped is only half the job.
One command lists where they put the items.
Click the coordinates and you're there, even in another world.
[IMG]https://raw.githubusercontent.com/ESMP-FUN/BetterAntiDupe/refs/heads/master/docs/.gitbook/assets/ledger-stash.png[/IMG]

More info? [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/using-it/when-an-alert-comes-in']Read about it in the docs[/URL]
[/SPOILER]
[SPOILER="Decide in five minutes"]
Every alert has [B][History][/B] and [B][Stash][/B] buttons.
Look, decide, done.
Confirm a duper, clear a false alarm, accept a legitimate item, or take the extras back.
[IMG]https://raw.githubusercontent.com/ESMP-FUN/BetterAntiDupe/refs/heads/master/docs/.gitbook/assets/alert-chat.png[/IMG]

More info? [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/using-it/when-an-alert-comes-in']Read about it in the docs[/URL]
[/SPOILER]
[SPOILER="Alerts on your phone"]
You don't have to be online to know.
Discord, Telegram, Slack, or your own webhook.
Only the serious alerts, and no spam. One command tests your setup.

More info? [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/using-it/alerts-outside-the-game']Read about it in the docs[/URL]
[/SPOILER]
[SPOILER="Watch first, act later"]
Nothing is taken from anyone until you say so.
Watch the alerts for a while. Once you trust them, turn on automatic removal.
It only ever takes the extras, never a whole stack.
[IMG]https://raw.githubusercontent.com/ESMP-FUN/BetterAntiDupe/refs/heads/master/docs/.gitbook/assets/ledger-remove.png[/IMG]

More info? [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/getting-started/watch-or-remove']Read about it in the docs[/URL]
[/SPOILER]
[SPOILER="Items stack normally (even from other plugins)"]
Your players won't notice a thing.
Watched items stack and trade like any other item.
Items handed out by auction houses, shops, orders menus, crates, graves, or kits automatically pick up the new holder's mark, so they stack right away with items the player already owns.

More info? [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/using-it/other-plugins']Read about it in the docs[/URL]
[/SPOILER]
[SPOILER="Hidden from players"]
Mods that show hidden item data see nothing, even inside shulker boxes, bundles, or sulfur cube buckets.
A duper testing on two accounts can't tell which items are watched.

More info? [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/reference/settings']Read about it in the docs[/URL]
[/SPOILER]
[SPOILER="Available in your language"]
English, Português do Brasil, Español, Deutsch, Русский and Polski are built in.
Every message can be changed to your liking.
[IMG]https://raw.githubusercontent.com/ESMP-FUN/BetterAntiDupe/refs/heads/master/docs/.gitbook/assets/tour-language.png[/IMG]

More info? [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/using-it/language-and-messages']Read about it in the docs[/URL]
[/SPOILER]
[SPOILER="Several servers?"]
Items travel between servers on a network.
Share records through Redis, and a dupe on one server is spotted on another.

More info? [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/getting-started/storage']Read about it in the docs[/URL]
[/SPOILER]
[CENTER][SIZE=3][COLOR=#808080]
------------------------------
[/COLOR][/SIZE][/CENTER]
[SIZE=6][COLOR=#0000ff][B]Installation[/B][/COLOR][/SIZE]
[LIST=1]
[*]Download the jar.
[*]Drop it into [ICODE]plugins/[/ICODE] and restart the server.
[*]Optional: join the server and run [ICODE]/adp setup[/ICODE] to walk through the main settings in game (or leave the defaults as they are).
[*]That's it. [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/help/testing-in-game']Testing in-game[/URL] shows you it working.
[/LIST]
More info? [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/getting-started/install']Read about it in the docs[/URL]
[CENTER][SIZE=3][COLOR=#808080]
------------------------------
[/COLOR][/SIZE][/CENTER]
[SIZE=6][COLOR=#0000ff][B]Commands[/B][/COLOR][/SIZE]
Every command starts with [ICODE]/adp[/ICODE].
[LIST]
[*][ICODE]/adp setup[/ICODE] - guided tour of the main settings ([ICODE]/adp setup continue[/ICODE] resumes)
[*][ICODE]/adp ledger suspects[/ICODE] - everyone currently suspected, worst first
[*][ICODE]/adp ledger reconcile <player>[/ICODE] - check what an online player carries right now
[*][ICODE]/adp ledger history <player>[/ICODE] - their last 15 records
[*][ICODE]/adp ledger stash <player>[/ICODE] - where they put the items, with click-to-teleport
[*][ICODE]/adp ledger remove <player>[/ICODE] - take back only the extras, after you confirm
[*][ICODE]/adp ledger accept <player> <item> [amount][/ICODE] - record an item they earned fairly and clear its alert
[*][ICODE]/adp ledger confirm <player>[/ICODE] - mark a real duper
[*][ICODE]/adp ledger clear <player>[/ICODE] - mark a false alarm
[*][ICODE]/adp ledger verify[/ICODE] - check nobody has edited the records
[*][ICODE]/adp fixstacks [player][/ICODE] - re-mark items so identical stacks merge again
[*][ICODE]/adp marks[/ICODE] - check owner mark signing status
[*][ICODE]/adp reload[/ICODE] - reload messages, alerts, and removal settings
[*][ICODE]/adp test alert[/ICODE] - send a test alert to chat and enabled channels
[*][ICODE]/adp update check[/ICODE] / [ICODE]download[/ICODE] - check for updates and stage the next version
[/LIST]
[ICODE]antidupe.alerts[/ICODE] only sees alerts.
[ICODE]antidupe.ledger[/ICODE] can use [ICODE]/adp ledger[/ICODE] commands.
[ICODE]antidupe.admin.setup[/ICODE] can run [ICODE]/adp setup[/ICODE].
[ICODE]antidupe.admin[/ICODE] gets all of the above, plus reload, marks, fixstacks, test alerts, and updates.

More info? [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/reference/commands-and-permissions']Read about it in the docs[/URL]
[CENTER][SIZE=3][COLOR=#808080]
------------------------------
[/COLOR][/SIZE][/CENTER]
[SIZE=6][COLOR=#0000ff][B]Free and source available[/B][/COLOR][/SIZE]
This release is free, with no licence key required.
The source is on [URL='https://github.com/ESMP-FUN/BetterAntiDupe']GitHub[/URL], and issues and pull requests are welcome.

[SPOILER="Anonymous statistics"]
Almost nobody opens a ticket, so I cannot tell which Minecraft versions run it.
Knowing that helps me fight dupes on those versions first.
[LIST]
[*][B]Sent:[/B] your settings, versions, and counts of dupes caught, removed and blocked.
[*][B]Never sent:[/B] addresses, server names, player names, or anything from your records.
[*][B]Kept private.[/B] Public numbers would help dupers pick unprotected servers.
[*][B]Error reports[/B] are cleaned of passwords, tokens and ids before they leave your server.
[/LIST]
Set [ICODE]metrics.enabled: false[/ICODE] to send nothing at all.
Nothing is ever shared, sold, or made public.

More info? [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/reference/settings']Read about it in the docs[/URL]
[/SPOILER]

[QUOTE]I take your privacy very seriously. Read the [URL='https://esmp.fun/plugins/privacy.php']company privacy policy[/URL].[/QUOTE]
[CENTER][SIZE=3][COLOR=#808080]
------------------------------
[/COLOR][/SIZE][/CENTER]
[SIZE=6][COLOR=#0000ff][B]Links[/B][/COLOR][/SIZE]
[LIST]
[*][B]Guide:[/B] [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe']esmp-fun.gitbook.io/plugins/better-anti-dupe[/URL]
[*][B]Troubleshooting:[/B] [URL='https://esmp-fun.gitbook.io/plugins/better-anti-dupe/help/troubleshooting']common problems[/URL]
[*][B]Source and issues:[/B] [URL='https://github.com/ESMP-FUN/BetterAntiDupe']github.com/ESMP-FUN/BetterAntiDupe[/URL]
[*][B]Changelog:[/B] [URL='https://github.com/ESMP-FUN/BetterAntiDupe/blob/master/CHANGELOG.md']CHANGELOG.md[/URL]
[/LIST]
[CENTER][SIZE=3][I]If BetterAntiDupe saved you from cleaning up a dupe wave, a positive review[/I]
[I]on this page is the best way to support development.[/I][/SIZE][/CENTER]
