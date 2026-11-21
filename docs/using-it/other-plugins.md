# Auction houses, shops and other plugins

This page is for you if your server runs an auction house, a shop, an orders board, crates, graves or kits. Most of it works by itself. Two plugins need one line changed in their own settings.

## What happens by itself

Every tracked item carries a hidden mark that says who holds it. When another plugin hands an item to a player, for example a diamond bought from the auction house, the item still carries the seller's mark, or none at all. It looks the same as the player's own diamonds, but the game will not stack them.

Better Anti-Dupe fixes this as the item arrives:

* The item gets the new holder's mark, so it stacks with what they already have.
* It is added to that player's record.
* It comes off the record of the player who had it before, so they cannot dupe that many and stay hidden.

This works with any plugin that hands out items from a menu or a command. These plugins are also followed directly, for the moments that use neither:

| Plugin | Also covered |
|---|---|
| zAuctionHouse | Buying, collecting purchases, taking back an expired or cancelled listing |
| DonutOrders | Delivering to an order, collecting an order |
| EconomyShopGUI | Buying, and selling with `/sellall` or the sell screen |
| PhoenixCrates and PhoenixCratesLite | Crate rewards |
| AxGraves | Collecting your own grave, looting someone else's |

{% hint style="info" %}
**Items with a faked mark are left alone.** They are not given a new mark, so staff still get the faked-mark alert for them.
{% endhint %}

## 1. Let DonutOrders accept marked items

Out of the box, DonutOrders refuses any item that carries data from another plugin. Players then see "You have nothing this order wants" while holding exactly what the order asks for.

1. Open `plugins/DonutOrders/config.yml`.
2. Find `ignored-plugin-data` under `matching`, and add the mark's name:

   ```yaml
   matching:
     ignored-plugin-data:
       - "antidupepro:*"
   ```
3. Run `/orderadmin reload`.

## 2. Let EconomyShopGUI buy marked items

Out of the box, EconomyShopGUI will not buy items that carry another plugin's data. `/sellall` then says "No items found which can be sold".

1. Open `plugins/EconomyShopGUI/config.yml`.
2. Find `sold-items-ignored-NBTtags`, and add `custom_data`:

   ```yaml
   sold-items-ignored-NBTtags:
     - 'repair_cost'
     - 'custom_data'
   ```
3. Restart the server.

## Did you rename the mark?

`antidupepro` in step 1 is the standard name of the mark. If you changed `ownership.namespace` in Better Anti-Dupe's `config.yml`, use your name there instead. For example, with `namespace: "data"`:

```yaml
matching:
  ignored-plugin-data:
    - "data:*"
```

Step 2 does not change. `custom_data` covers every name.

## 3. Check it

1. Hold a few diamond blocks.
2. Buy one more diamond block from the auction house or the shop.
3. It lands on your stack instead of in a slot of its own.

{% hint style="success" %}
**That's the whole setup.**
{% endhint %}

## Good to know

* **zAuctionHouse keeps purchases until they are collected.** A bought item waits under "Purchased items" in `/ah`. It gets its new mark when the buyer collects it.
* **Items that sit in a plugin are still on the seller's record.** An auction listing or an order delivery comes off the seller's record once someone else receives it. If nobody ever collects it, it stays on.
* **Older stacks that still will not stack** can be fixed with `/adp fixstacks <player>`. See [Commands & permissions](../reference/commands-and-permissions.md).

<details>

<summary>Prefer to switch it off?</summary>

Set this in Better Anti-Dupe's `config.yml` and restart:

```yaml
ownership:
  mark_plugin_deliveries: false
```

Items from other plugins then keep the mark they arrived with, and will not stack with the player's own items. See [Settings](../reference/settings.md).

</details>

Next: [Language and messages](language-and-messages.md).
