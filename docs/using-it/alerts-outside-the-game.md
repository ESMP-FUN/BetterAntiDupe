# Alerts in Discord, Telegram or Slack

Admins in game always get alerts. This page gets the same alerts to wherever you actually look when you are not playing. Pick your app and follow its steps. Each takes a couple of minutes.

All of this lives in `config.yml` under `[Part 1]`, and all of it is off until you fill something in.

## Discord

1. In Discord, open the settings of the channel where alerts should appear (the gear icon).
2. Go to **Integrations**, then **Webhooks**, then **New Webhook**.
3. Press **Copy Webhook URL**.
4. In `config.yml`:

   ```yaml
   notifications:
     discord:
       enabled: true
       webhook_url: "PASTE-THE-URL-HERE"
   ```
5. Run `/adp reload`, then `/adp test alert`.

Alerts arrive as coloured boxes, red for the most serious.

## Telegram

1. In Telegram, message **@BotFather**, send `/newbot`, and follow its steps. At the end it gives you a **bot token**, which looks like `123456:ABC-DEF...`.
2. Add your new bot to the group or channel where alerts should appear.
3. Add **@getidsbot** to that same group. It replies with the chat id. Group ids usually start with `-100`.
4. In `config.yml`:

   ```yaml
   notifications:
     telegram:
       enabled: true
       bot_token: "PASTE-THE-TOKEN-HERE"
       chat_id: "-100..."
   ```
5. Run `/adp reload`, then `/adp test alert`.

## Slack

1. Create an **Incoming Webhook** at [api.slack.com/messaging/webhooks](https://api.slack.com/messaging/webhooks) and pick the channel.
2. Copy the webhook URL.
3. In `config.yml`:

   ```yaml
   notifications:
     slack:
       enabled: true
       webhook_url: "PASTE-THE-URL-HERE"
   ```
4. Run `/adp reload`, then `/adp test alert`.

{% hint style="warning" %}
**Keep these URLs and tokens private.** Anyone who has one can post in your channel. Do not share your `config.yml` publicly.
{% endhint %}

`/adp test alert` tells you, for each app, whether the alert was delivered or why it failed. More on [Testing in-game](../help/testing-in-game.md#are-discord-telegram-or-slack-alerts-arriving).

## How many alerts you get

By default you only hear about `HIGH` and `CRITICAL` alerts, and never the same player and item twice within 30 seconds. So one incident is one message, not fifty.

<details>

<summary>Change which alerts are sent (<code>notifications.min_severity</code>)</summary>

**Default:** `HIGH`

Use `CRITICAL` for only the most certain ones, or `LOW` to see everything, small wobbles included.

</details>

<details>

<summary>Change the quiet time between repeats (<code>notifications.rate_limit_seconds</code>)</summary>

**Default:** `30`

How many seconds to wait before sending another alert about the same player and item.

</details>

<details>

<summary>Anything else: n8n, Zapier, your own bot</summary>

Turn on `generic` and set `url`:

```yaml
notifications:
  generic:
    enabled: true
    url: "https://your-service.example/hook"
```

Every alert is sent there as a POST with this body:

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

</details>

<details>

<summary>What is a <code>playerUuid</code>?</summary>

A UUID is the permanent id Minecraft gives every account. A player can change their name, but their UUID never changes, which makes it the reliable way to know who someone is.

</details>

## Good to know

* **It cannot slow your server.** Alerts are sent in the background. If Discord or Telegram is down, the game carries on, and the console warns you once a minute until it works again.
* **Alerts follow your language.** The text comes from your `messages.yml`, with colour codes removed.
