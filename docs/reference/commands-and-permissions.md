# Commands & permissions

Every command starts with `/adp`. `/antidupe` and `/betterantidupe` work too.

## For everyone

| Command     | What it does            |
| ----------- | ----------------------- |
| `/adp help` | Lists the commands you can use |

## Looking into players (`antidupe.ledger`, default: op)

Names of players who have logged off work as well, except for `reconcile` and `remove`.

| Command                          | What it does |
| -------------------------------- | ------------ |
| `/adp ledger suspects`           | Everyone currently suspected, worst first |
| `/adp ledger reconcile <player>` | Count what an online player carries right now and compare it to their record |
| `/adp ledger remove <player>`    | Show what an online player carries extra, with a button to take only that back |
| `/adp ledger history <player>`   | Their last 15 records |
| `/adp ledger stash <player>`     | Their last 20 times putting tracked items away, with clickable coordinates to teleport there |
| `/adp ledger balance <player>`   | How many of each tracked item their record says they should have |
| `/adp ledger witness <player>`   | How often other players were nearby when they gained things |
| `/adp ledger trust <player>`     | Their trust score out of 100 |
| `/adp ledger confirm <player>`   | Mark them as a real duper. Runs your punishment command if you set one. |
| `/adp ledger clear <player>`     | Mark a false alarm. Resets their suspicion. |
| `/adp ledger status`             | How many suspects there are right now, and their names |
| `/adp ledger verify`             | Check nobody has edited the records by hand |

Not sure how these fit together? [When an alert comes in](../using-it/when-an-alert-comes-in.md) walks through them in order.

## Running the plugin (`antidupe.admin`, default: op)

| Command               | What it does |
| --------------------- | ------------ |
| `/adp reload`          | Apply changes to `messages.yml`, alerts and removal settings without a restart. It tells you if anything you changed still needs one. |
| `/adp test alert`      | Send a test alert to your chat and to every Discord, Telegram, Slack or webhook you turned on |
| `/adp update check`    | See whether a newer version is out |
| `/adp update download` | Download it, ready for the next restart |
| `/adp update restore`  | Go back to the version you had before |
| `/adp update status`   | Show the current update state |

## Permissions

| Permission                | Default | What it does |
| ------------------------- | ------- | ------------ |
| `antidupe.alerts`         | op      | See dupe alerts in chat. No commands. |
| `antidupe.ledger`         | op      | Use every `/adp ledger` command |
| `antidupe.admin`          | op      | Both of the above, plus `/adp reload`, `/adp test` and `/adp update` |
| `antidupe.witness.exempt` | nobody  | Never counted as someone who saw a player gain something. Give it to vanished staff. |
| `antidupe.tag.view`       | nobody  | See the plugin's hidden mark on items in your own game, from your next login. For troubleshooting. |

{% hint style="info" %}
**Splitting duties with LuckPerms:** give helpers `antidupe.alerts` so they can shout when something comes in, moderators `antidupe.ledger` so they can investigate, and keep `antidupe.admin` for yourself.
{% endhint %}
