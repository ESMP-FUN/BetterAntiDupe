# BetterAntiDupe

Stops item duplication on your Minecraft server, and tells you who tried.

It keeps a record of every valuable item a player gains and loses. When someone is carrying more than their record can explain, you get an alert, a list of where they put the items, and a click to teleport there. The classic dupe machines (rail, carpet, TNT, sand and restart dupers) are simply not allowed to work.

The defaults are sensible. Most servers install it and never open the config.

## Which applies to you?

* **I just want it running.** [Install](getting-started/install.md). Two minutes.
* **I run several servers behind a proxy.** Install first, then [Pick where records are kept](getting-started/storage.md).
* **I got an alert and want to know what to do.** [When an alert comes in](using-it/when-an-alert-comes-in.md).
* **I want duped items taken back automatically.** [Watch only, or take dupes back](getting-started/watch-or-remove.md).

## Will it work on my server?

|                   |                                                                  |
| ----------------- | ---------------------------------------------------------------- |
| Server software   | **Paper**, **Folia**, **Spigot**, or a Paper fork like **Purpur** |
| Minecraft version | **1.21.x** with the plain download, **26.x** with the `-mc26` download |
| Java              | **21 or newer** for 1.21.x, **25 or newer** for 26.x              |
| Anything else     | Nothing. Redis is only needed if several servers share records.  |

{% hint style="warning" %}
**It does not work on Fabric, Forge or NeoForge**, and not on Minecraft 1.20 or older.

**Pick the right download.** The plain jar is for 1.21.x and the `-mc26` jar is for 26.x. The wrong one will not start.
{% endhint %}

## Where to start

* **Setting up**: [Install](getting-started/install.md), then [Watch only, or take dupes back](getting-started/watch-or-remove.md)
* **Day to day**: [When an alert comes in](using-it/when-an-alert-comes-in.md) and [Alerts in Discord, Telegram or Slack](using-it/alerts-outside-the-game.md)
* **Making sure it works**: [Testing in-game](help/testing-in-game.md)

Every setting, command and permission is under **Reference**. Something not working? [Troubleshooting](help/troubleshooting.md) has the fix.
