# Use it from your plugin

BetterAntiDupe has a small API for other plugins. Use it when your plugin hands out items, takes items away, or copies items from a template, so those items are never mistaken for dupes. You can also listen to its alerts and protections.

It works on Spigot, Paper and Folia, from Minecraft 1.21 up.

## Add the dependency

1. Add the API to your build. It is only needed to compile, because BetterAntiDupe brings it to the server.

   Gradle (Kotlin):

   ```kotlin
   repositories {
       maven("https://jitpack.io")
   }

   dependencies {
       compileOnly("com.github.ESMP-FUN.BetterAntiDupe:betterantidupe-api:v4.7.0")
   }
   ```

   Maven:

   ```xml
   <repository>
       <id>jitpack.io</id>
       <url>https://jitpack.io</url>
   </repository>

   <dependency>
       <groupId>com.github.ESMP-FUN.BetterAntiDupe</groupId>
       <artifactId>betterantidupe-api</artifactId>
       <version>v4.7.0</version>
       <scope>provided</scope>
   </dependency>
   ```

2. Add BetterAntiDupe to your `plugin.yml`, so it loads first:

   ```yaml
   softdepend: [BetterAntiDupe]
   ```

   Use `depend` instead if your plugin cannot run without it.

3. Get the API once BetterAntiDupe is enabled:

   ```java
   if (BetterAntiDupeProvider.isAvailable()) {
       BetterAntiDupeApi api = BetterAntiDupeProvider.get();
   }
   ```

{% hint style="info" %}
With `softdepend`, keep every BetterAntiDupe call in its own class and only touch that class after `isAvailable()` is true. Otherwise your plugin fails to load on servers without BetterAntiDupe.
{% endhint %}

## Common tasks

<details>

<summary><strong>You give items to a player</strong> (rewards, shop sales, kits, crates)</summary>

Call this once per handout, right after the items are added:

```java
api.ledger().recordGrant(player.getUniqueId(), Material.DIAMOND, 5, "MyShop");
```

The last part is a short label that shows in the player's history. Items that are not tracked are ignored, so you can call it for every handout without checking first.

</details>

<details>

<summary><strong>You take items from a player</strong> (sell shops, custom recipes, taxes)</summary>

Pass the stacks exactly as you removed them:

```java
api.ledger().recordRemoval(player.getUniqueId(), removedStacks, "MyShop");
```

Only items marked as this player's are counted, including what is inside shulker boxes and bundles. Without this call, the record keeps expecting items the player no longer has, and a later dupe of the same amount would go unnoticed.

</details>

<details>

<summary><strong>You copy items from a template</strong> (shop displays, saved kits, crate previews)</summary>

A copy of a player's item also copies its owner mark, so every copy would look like it belongs to that player. Remove the mark from the copy:

```java
ItemStack copy = original.clone();
api.ownership().strip(copy);
```

This also clears marks on items inside shulker boxes and bundles.

</details>

<details>

<summary><strong>You want to know when an alert fires</strong></summary>

```java
@EventHandler
public void onAlert(DupeAlertEvent event) {
    // event.getPlayerName(), event.getKind(), event.getMaterial(), event.getSeverity()
}
```

The event arrives off the main thread. Cancel it to drop the alert completely, for example when your plugin knows those items are legitimate.

</details>

<details>

<summary><strong>A protection gets in the way of your plugin</strong></summary>

`DupeBlockedEvent` fires just before a protection stops something. Cancel it to let that one action through:

```java
@EventHandler
public void onBlocked(DupeBlockedEvent event) {
    if (event.getProtection() == Protection.HIDDEN_MENU && isMyMenu(event.getPlayer())) {
        event.setCancelled(true);
    }
}
```

</details>

<details>

<summary><strong>You want to stop automatic removal for a player</strong></summary>

With `auto_delete_dupes` on, `DupeRemovalEvent` fires on the player's thread just before duped items are taken. Cancel it to leave them.

</details>

## Good to know

* Methods that read the records return a `CompletableFuture`, which completes off the main thread. Hop back to the main thread before touching the world.
* `api.isTracking()` is false when item tracking could not start. The protections still run, and the record methods do nothing.
* `BetterAntiDupeApi.API_LEVEL` goes up whenever the API gains something. Compare it with `api.apiLevel()` before using anything newer than the version you built against.
* New alert kinds and protections may be added later, so keep a `default` branch when you switch on them.
