# Language and messages

## Switch to another language

Six languages are built in. In `config.yml`, set:

```yaml
language: de
```

| Code    | Language            |
| ------- | ------------------- |
| `en`    | English (default)   |
| `pt_BR` | Português do Brasil |
| `es`    | Español             |
| `de`    | Deutsch             |
| `ru`    | Русский             |
| `pl`    | Polski              |

Run `/adp reload`. Anything a translation is missing shows in English, so switching never breaks anything.

Spotted an awkward phrase? Corrections are very welcome on [GitHub](https://github.com/ESMP-FUN/BetterAntiDupe/issues).

## Change a message

Every message players and admins see comes from `plugins/BetterAntiDupe/messages.yml`.

1. Open `messages.yml` and find the line you want to change.
2. Edit the text. Keep anything in `{curly brackets}`, like `{player}`. The plugin fills those in, and you can move them anywhere in the sentence.
3. Run `/adp reload`.

Your edits always win over the built-in language. **Delete any line you did not change**: it falls back to the built-in text, and future updates can add new messages without touching yours.

<details>

<summary>Colours and bold</summary>

Use `&` codes: `&c` is red, `&e` yellow, `&a` green, `&l` bold, `&r` back to normal.

```yaml
alerts:
  broadcast: "&c&l[DUPE] &e{player} &7({type}) &f{material}: &c{details}"
```

</details>

{% hint style="info" %}
**The console stays in English on purpose.** That keeps error messages searchable, and makes it easier to get help when something goes wrong.
{% endhint %}
