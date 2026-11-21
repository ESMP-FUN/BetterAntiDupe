# Settings the tour doesn't ask about

The tour covers what most servers change. These are the next ones people look
for. Each lives in `config.yml`, and the line right above it in the file says
what it does.

After editing, run `/adp reload`. If a change still needs a restart, the reload
says so.

## Ban or punish when you confirm a duper

When you run `/adp ledger confirm <player>`, this console command runs too.
`{player}` becomes their name:

```yaml
on_confirm_command: "tempban {player} 7d Item duplication"
```

It only runs when **you** confirm someone. The plugin never punishes anyone on
its own.

## Watch other items

Diamonds, netherite, enchanted books, shulker boxes and the other valuables are
watched already. To add your own, see [Choosing what gets
tracked](../using-it/what-gets-tracked.md).

## Turn one dupe block off

Each dupe machine has its own switch under `[Blocking]`, for example:

```yaml
prevent-rail-dupers: true
```

Turn one off only if it gets in the way of a farm your players are allowed to
build. [What it blocks and catches](../using-it/what-it-blocks-and-catches.md)
says what each one stops. These need a restart.

## Hide the plugin from players

Players never see the plugin's mark on their items, because
`hide_tag_from_clients` ships on. Leave it that way. The other options under
`[Look]` are in [Settings](../reference/settings.md#hiding-the-plugin-from-players).

## Stop the reminder

Admins are reminded about the tour when they join, at most once a week and
three times in all. To never remind anyone:

```yaml
setup:
  reminder:
    enabled: false
```

## Everything else

[Settings](../reference/settings.md) lists the rest, chapter by chapter.

Next: [More than one server: share records](storage.md).
