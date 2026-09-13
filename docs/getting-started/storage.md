# Pick where records are kept

**One server?** You are already done. The plugin keeps its records in a file called `ledger.db` and needs nothing else. Skip this page.

**Several servers behind BungeeCord or Velocity?** Read on. Players carry items from one server to another, so every server needs to see the same records. Otherwise a diamond duped on your survival server and carried to creative looks brand new when it arrives. Redis is how the servers share.

## 1. Have a Redis running

Your host may offer one, or you can run your own. Note its address, port and password if it has one.

## 2. Point every server at it

In `config.yml` on **every** server, find `[Part 3]` and change these lines:

```yaml
storage:
  backend: REDIS

redis:
  host: "10.0.0.5"
  port: 6379
  database: 1
  password: ""
```

Use the same `host`, `port` and `database` on all of them. `database` is just a number that keeps these records apart from anything else on that Redis.

## 3. Restart each server

The console says it connected. If it could not reach Redis, the plugin does not start and the console says so. See [Troubleshooting](../help/troubleshooting.md).

{% hint style="warning" %}
**Switching does not bring your old records along.** Records already in `ledger.db` stay there. Players simply start fresh in Redis, which is harmless: anything they already own is counted the first time they are checked.
{% endhint %}

<details>

<summary>What about MEMORY?</summary>

`backend: MEMORY` keeps records in memory only and throws them away on every restart. It is for testing the plugin on a throwaway server. Never use it on a server people play on, because after each restart the plugin has no idea what anyone owns.

</details>
