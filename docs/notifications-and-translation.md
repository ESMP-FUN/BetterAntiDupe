# Notifications & Translation

This short guide covers the two "make it yours" features: getting dupe alerts
**outside the game** (Discord, Telegram, Slack, custom webhooks — since 3.3.4)
and **translating or restyling** every in-game message (since 3.3.3). For
installation, detection and admin commands, see the [main user guide](README.md).

## 1. Alerts outside the game (webhooks)

BetterAntiDupe can ping you when a dupe is detected, even when no admin is
online. Everything lives in the `notifications` section of `config.yml`, and
everything is **off by default**:

```yaml
notifications:
  # Only send alerts at or above this severity: LOW, MEDIUM, HIGH, CRITICAL.
  min_severity: HIGH

  # Don't re-send the same kind of alert more often than this (seconds).
  rate_limit_seconds: 30

  discord:
    enabled: false
    webhook_url: ""

  telegram:
    enabled: false
    bot_token: ""
    chat_id: ""

  slack:
    enabled: false
    webhook_url: ""

  generic:
    enabled: false
    url: ""
```

**Upgrading from an older version?** Your existing `config.yml` is kept as-is,
so this section won't appear by itself — copy the block above into your file,
enable what you use, and restart.

## 2. Discord setup

1. In Discord, open the channel where alerts should appear.
2. Channel settings (gear icon) → **Integrations** → **Webhooks** →
   **New Webhook**.
3. Click **Copy Webhook URL**.
4. In `config.yml`: set `discord.enabled: true` and paste the URL into
   `webhook_url`.
5. Restart the server. Alerts now arrive as colored Discord embeds
   (red = critical).

## 3. Telegram setup

1. In Telegram, message **@BotFather**, send `/newbot`, and follow the steps.
   You get a **bot token** (looks like `123456:ABC-DEF...`).
2. Add your new bot to the group or channel where alerts should appear.
3. Find the chat id: add **@getidsbot** (or @userinfobot) to the same group —
   it tells you the id (group ids usually start with `-100`).
4. In `config.yml`: set `telegram.enabled: true`, paste the token into
   `bot_token` and the id into `chat_id`.
5. Restart the server.

## 4. Slack setup

1. Create an **Incoming Webhook** for your workspace:
   [api.slack.com/messaging/webhooks](https://api.slack.com/messaging/webhooks).
2. Pick the channel, copy the webhook URL.
3. In `config.yml`: set `slack.enabled: true` and paste the URL into
   `webhook_url`.
4. Restart the server.

## 5. Custom webhook (everything else)

For anything not listed above — n8n, Zapier, Make, a home-grown bot — enable
`generic` and set `url`. BetterAntiDupe sends an HTTP POST with this JSON body
for every alert:

```json
{
  "plugin": "BetterAntiDupe",
  "type": "BALANCE_DISCREPANCY",
  "severity": "CRITICAL",
  "player": "Steve",
  "playerUuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
  "material": "ENCHANTED_BOOK",
  "details": "Has 12 but ledger shows 4 (excess: 8)",
  "timestamp": 1781234567890
}
```

## 6. Good to know

- **min_severity** — `HIGH` (the default) keeps small wobbles out of your
  channel. Use `CRITICAL` if you only want the highest-confidence detections,
  or `LOW` to see everything.
- **Burst protection** — the same kind of alert (same player + same item) is
  sent at most once per `rate_limit_seconds`. A detection burst becomes one
  message, not a hundred.
- **It can't hurt the server** — sending happens in the background. If a
  webhook is broken or unreachable, the game is unaffected; the console gets
  one warning per minute until it works again.
- **Language** — notification text follows your `messages.yml`, with color
  codes removed.
- **Keep webhook URLs secret** — anyone who has the URL can post to your
  channel. Don't commit your `config.yml` to a public repo.

## 7. Translating the plugin (messages.yml)

### Built-in translations (3.3.5+)

Five translations ship with the plugin. Pick one with a single line in
`config.yml`:

```yaml
language: pt_BR   # or: en, es, de, ru, pl
```

| Code | Language |
|---|---|
| `en` | English (default) |
| `pt_BR` | Português do Brasil |
| `es` | Español |
| `de` | Deutsch |
| `ru` | Русский |
| `pl` | Polski |

Your own edits in `messages.yml` always win over the chosen translation, and
anything a translation is missing falls back to English — so switching
languages never breaks anything. Spotted an awkward phrase? Corrections are
very welcome as GitHub issues or pull requests.

### Custom translation / restyling

Everything the plugin shows in-game — dupe alerts and all `/adp` command
output — comes from `plugins/BetterAntiDupe/messages.yml`. The file is created
on first start with the English defaults.

- **Edit any line.** Colors use `&` codes (`&c` = red, `&l` = bold,
  `&r` = reset).
- **Keep the `{placeholders}`** (like `{player}` or `{material}`) — the plugin
  fills them in. You can move them anywhere in the sentence, which matters for
  languages with different word order.
- **Delete what you don't customise.** Any missing line falls back to the
  built-in English text. This also means plugin updates can add new messages
  without ever breaking your translation.
- **Console logs stay English on purpose** — that keeps error messages
  searchable and makes it easier to get support.

Example — turning the alert German:

```yaml
alerts:
  broadcast: "&c&l[DUPE] &e{player} &7({type}) &f{material}: &c{details}"
  balance-discrepancy: "Hat {actual}, aber das Kassenbuch zeigt {expected} (Überschuss: {excess})"
```

Changes take effect on the next server restart.

---

_Last updated for BetterAntiDupe 4.2.0 — see the [main user guide](README.md)
for everything else._
