# CelestCombat-Xtra

**CelestCombat-Xtra** is a comprehensive combat management plugin for **SwordPvP, CrystalPvP, and competitive Minecraft servers**.

It prevents combat logging, blocks escape mechanics, integrates with major protection plugins, and provides advanced item restriction systems while remaining highly configurable for PvP balance.

---

# ⚔️ Smart Combat System

* **Intelligent Combat Tagging** with configurable duration
* **Whitelist or Blacklist command blocking** during combat
* **Admin action protection** – staff kicks/bans won't trigger punishments
* **Flight control** – disable creative flight while tagged
* **Optional nametag prefix/suffix** showing opponent and time
* **Optional boss bar** for combat countdown (off by default)

![Combat Timer](assets/combattimer.png)

*Combat indicator and cooldowns displayed during PvP engagements*

---

# 🎯 Advanced PvP Restriction Control

CelestCombat-Xtra gives full control over PvP escape mechanics and item balance.

### Ender Pearl Systems

* Combat timer refresh when pearls land
* **Ender Pearl cooldown system**
* Per-world cooldown control
* Block pearls entirely in combat

### Trident Restrictions

* Per-world trident bans
* Configurable cooldown system
* Combat timer refresh support
* Block throw/riptide in combat

### Combat Item Restrictions

Block or cooldown specific items during combat:

* Elytra (with abuse prevention – glide/firework block, strike counter)
* Chorus Fruit
* Wind charges
* Maces
* Spears (1.21+)
* Custom configurable items

![Item Cooldowns](assets/itemcooldowns.png)

*Multiple configurable cooldowns displayed during PvP*

---

# 🛡️ Elytra Abuse Prevention

Advanced elytra exploit protection for PvP servers.

* **Glide & firework blocking** in combat
* **Strike counter** – penalize repeated attempts (temp break or drop)
* Configurable strikes before action
* Full inventory handling (TEMP_BREAK or DROP)

---

# 👶 New Player Protection

Optional protection system for new players joining the server.

* Configurable protection duration
* PvP protection
* Optional mob damage protection
* Automatic removal when player attacks someone
* Boss bar and action bar protection indicators
* Per-world settings

![New Player Protection](assets/newplayerprotection.png)

*PvP protection display for new players*

---

# 🛡️ WorldGuard SafeZone Protection

CelestCombat-Xtra integrates with **WorldGuard** to prevent players from escaping combat through protected regions.

* Visual safe-zone barriers
* Customizable barrier materials
* Configurable barrier height and detection radius
* Automatic push-back system
* Block chorus fruit teleport into safe zones
* Per-world protection settings

All barriers are rendered **client-side for minimal server impact**.

![Safe Zone Barrier](assets/safezonebarrier.png)

*Safe zone barrier visualization near no-PvP regions*

---

# 🏆 Kill Reward System

Reward players for PvP victories.

* Run commands on player kills
* Global cooldown support
* Same-player farming protection
* Rich placeholders: `%killer%`, `%victim%`, `%killer_uuid%`, `%victim_uuid%`, `%world%`, `%x%`, `%y%`, `%z%`, health values, and more

Example reward command:

```
donutcratecore shards give %killer% 10
```

---

# 🔌 Plugin Integrations

## PlaceholderAPI

Full PlaceholderAPI support for scoreboards, tab lists, holograms, and chat.

* `%celestcombat_in_combat%` – combat state
* `%celestcombat_time_left%` – remaining seconds
* `%celestcombat_opponent%` – opponent name
* `%celestcombat_pearl_cooldown%`, `%celestcombat_trident_cooldown%`, `%celestcombat_wind_cooldown%`
* Ready flags for each cooldown

---

## WorldGuard

* Combat safe-zone barriers
* Prevent entry into protected regions
* Client-side barrier rendering
* Configurable push-back force

---

## GriefPrevention

* Claim entry blocking during combat
* Visual claim barriers
* Permission-based bypass system
* Per-world configuration support

---

# ⚡ Per-World Control

Configure features per world for minigames, lobbies, and PvP arenas.

* **Item limiter** – enable/disable per world
* **Enchant limiter** – enable/disable per world
* **Ender pearl** – cooldown and block per world
* **Trident** – cooldown and banned worlds per world
* **Newbie protection** – per-world duration and enable

---

# ⚡ Built for Performance

CelestCombat-Xtra is designed for **high-population PvP environments**.

* Configurable event priorities
* Optimized countdown timer (single global task)
* Client-side barrier rendering
* Per-world system controls
* Folia support

This ensures **minimal server impact even on large PvP networks**.
