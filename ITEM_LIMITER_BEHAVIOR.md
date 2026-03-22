# Item Limiter – What It Does

A per-player material limit system. Players cannot hold more than the configured amount of each limited material.

---

## Features

| # | Feature | Description |
|---|---------|-------------|
| 1 | **Per-material limits** | Configure max amount per material (e.g. DIAMOND: 64, TOTEM_OF_UNDYING: 1) |
| 2 | **Ground pickup** | Limits items picked up from the ground; allows partial pickup when under limit |
| 3 | **Shift-click from containers** | Limits items taken from chests etc.; allows partial transfer when under limit |
| 4 | **Inventory click / cursor** | Blocks putting items into player inventory that would exceed limit; correct handling for swaps |
| 5 | **Inventory drag** | Blocks dragging items into player inventory over limit |
| 6 | **Crafting** | Blocks crafting result if it would exceed limit; shift-click uses max craftable amount |
| 7 | **Furnace / brewer result** | Blocks taking from result slot if over limit |
| 8 | **Bundle contents** | Counts items inside bundles; removes excess from bundles when over limit |
| 9 | **Hopper → player** | Blocks hoppers from moving limited items into player inventory |
| 10 | **Fishing** | Blocks catching limited items when over limit |
| 11 | **Periodic scan** | Runs every N ticks, removes excess and optionally drops it |
| 12 | **Offhand exempt** | Offhand slot is not counted or touched; totems etc. in offhand are safe |

---

## What Gets Blocked / Removed

- **Pickup** – Fully blocked if at limit; partial pickup if room for some  
- **Shift-click from chest** – Partial transfer allowed (takes max possible)  
- **Click / drag into inv** – Blocked if would exceed limit  
- **Crafting** – Blocked if result would exceed limit  
- **Excess in inv** – Removed by periodic scan (dropped or deleted by config)

---

## Bypass

- **Permission** – `celestcombatxtra.bypass.item_limit` (configurable)  
- **OP** – Optional; `op_bypasses: false` means OPs are also limited

---

## Config Options

| Option | Default | Purpose |
|--------|---------|---------|
| `enabled` | false | Master toggle |
| `limits` | {...} | Material → max count map |
| `drop_excess` | true | Drop excess at feet vs delete |
| `scan_interval_ticks` | 10 | How often the scan runs |
| `players_per_tick` | 8 | Max players scanned per tick (spreads load) |
| `swap_grace_ticks` | 20 | Skip scanning after F-key hand swap |
| `message_interval` | 100 | Send excess_removed every N removals (0 = never) |
| `deny_message_cooldown_ticks` | 100 | Min ticks between deny messages per player/material |
| `send_excess_messages` | true | Toggle excess_removed messages |

---

## Optimizations

- **One-pass count** – Single inventory scan for all limited materials  
- **Batch scan** – Players scanned in groups across ticks  
- **Early exit** – Skip players with no limited materials  
- **Message throttling** – Deny and excess messages heavily rate-limited  
