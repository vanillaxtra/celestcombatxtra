package com.shyamstudio.celestcombatXtra;

import com.shyamstudio.celestcombatXtra.api.CelestCombatAPI;
import com.shyamstudio.celestcombatXtra.api.CombatAPIImpl;
import org.bstats.bukkit.Metrics;
import com.shyamstudio.celestcombatXtra.combat.CombatManager;
import com.shyamstudio.celestcombatXtra.combat.DeathAnimationManager;
import com.shyamstudio.celestcombatXtra.commands.CommandManager;
import com.shyamstudio.celestcombatXtra.commands.PvpCommand;
import com.shyamstudio.celestcombatXtra.configs.TimeFormatter;
import com.shyamstudio.celestcombatXtra.highlight.PvpHighlightManager;
import com.shyamstudio.celestcombatXtra.hooks.husksync.HuskSyncHook;
import com.shyamstudio.celestcombatXtra.hooks.protection.GriefPreventionHook;
import com.shyamstudio.celestcombatXtra.hooks.protection.LandsHook;
import com.shyamstudio.celestcombatXtra.hooks.protection.WorldGuardHook;
import com.shyamstudio.celestcombatXtra.language.LanguageManager;
import com.shyamstudio.celestcombatXtra.language.MessageService;
import com.shyamstudio.celestcombatXtra.listeners.CombatListeners;
import com.shyamstudio.celestcombatXtra.listeners.EnderPearlListener;
import com.shyamstudio.celestcombatXtra.listeners.EnderchestLister;
import com.shyamstudio.celestcombatXtra.listeners.ElytraCombatAbuseListener;
import com.shyamstudio.celestcombatXtra.listeners.EnchantLimiterListener;
import com.shyamstudio.celestcombatXtra.listeners.ExplosiveControlsListener;
import com.shyamstudio.celestcombatXtra.listeners.ItemRestrictionListener;
import com.shyamstudio.celestcombatXtra.listeners.PvpToggleListener;
import com.shyamstudio.celestcombatXtra.listeners.TeleportGraceListener;
import com.shyamstudio.celestcombatXtra.listeners.TridentListener;
import com.shyamstudio.celestcombatXtra.protection.NewbieProtectionManager;
import com.shyamstudio.celestcombatXtra.pvp.PvpToggleManager;
import com.shyamstudio.celestcombatXtra.pvp.TeleportGraceManager;
import com.shyamstudio.celestcombatXtra.rewards.KillRewardManager;
import com.shyamstudio.celestcombatXtra.storage.PvpStorage;
import com.shyamstudio.celestcombatXtra.storage.StorageFactory;
import com.shyamstudio.celestcombatXtra.updates.ConfigUpdater;
import com.shyamstudio.celestcombatXtra.updates.LanguageUpdater;
import com.shyamstudio.celestcombatXtra.updates.UpdateChecker;
import com.sk89q.worldguard.WorldGuard;

import net.kyori.adventure.text.Component;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

@Getter
@Accessors(chain = false)
public class CelestCombatPro extends JavaPlugin {
  @Getter
  private static CelestCombatPro instance;
  private final boolean debugMode = getConfig().getBoolean("debug", false);
  private LanguageManager languageManager;
  private MessageService messageService;
  private UpdateChecker updateChecker;
  private ConfigUpdater configUpdater;
  private LanguageUpdater languageUpdater;
  private TimeFormatter timeFormatter;
  private CommandManager commandManager;
  private CombatManager combatManager;
  private KillRewardManager killRewardManager;
  private CombatListeners combatListeners;
  private EnderchestLister enderchestLister;
  private EnderPearlListener enderPearlListener;
  private TridentListener tridentListener;
  private DeathAnimationManager deathAnimationManager;
  private NewbieProtectionManager newbieProtectionManager;
  private WorldGuardHook worldGuardHook;
  private GriefPreventionHook griefPreventionHook;
  private LandsHook landsHook;
  private HuskSyncHook huskSyncHook;
  private CombatAPIImpl combatAPI;
  private PvpStorage pvpStorage;
  private PvpToggleManager pvpToggleManager;
  private PvpHighlightManager pvpHighlightManager;
  private PvpToggleListener pvpToggleListener;
  private TeleportGraceManager teleportGraceManager;

  public static boolean hasWorldGuard = false;
  public static boolean hasGriefPrevention = false;
  public static boolean hasLands = false;
  public static boolean hasHuskSync = false;

  @Override
  public void onEnable() {
    long startTime = System.currentTimeMillis();
    instance = this;

    saveDefaultConfig();

    languageManager = new LanguageManager(this, LanguageManager.LanguageFileType.MESSAGES);
    languageUpdater = new LanguageUpdater(this, LanguageUpdater.LanguageFileType.MESSAGES);
    languageUpdater.checkAndUpdateLanguageFiles();

    messageService = new MessageService(this, languageManager);
    updateChecker = new UpdateChecker(this);
    configUpdater = new ConfigUpdater(this);
    configUpdater.checkAndUpdateConfig();
    timeFormatter = new TimeFormatter(this);

    deathAnimationManager = new DeathAnimationManager(this);
    combatManager = new CombatManager(this);
    killRewardManager = new KillRewardManager(this);
    newbieProtectionManager = new NewbieProtectionManager(this);
    combatListeners = new CombatListeners(this);
    getServer().getPluginManager().registerEvents(combatListeners, this);

    enderchestLister = new EnderchestLister(this);
    getServer().getPluginManager().registerEvents(enderchestLister, this);

    enderPearlListener = new EnderPearlListener(this, combatManager);
    getServer().getPluginManager().registerEvents(enderPearlListener, this);

    tridentListener = new TridentListener(this, combatManager);
    getServer().getPluginManager().registerEvents(tridentListener, this);

    getServer().getPluginManager().registerEvents(new ItemRestrictionListener(this, combatManager), this);
    getServer().getPluginManager().registerEvents(new ElytraCombatAbuseListener(this, combatManager), this);
    getServer().getPluginManager().registerEvents(new ExplosiveControlsListener(this), this);
    getServer().getPluginManager().registerEvents(new EnchantLimiterListener(this), this);

    // Protection-plugin detection (WorldGuard/GriefPrevention/Lands/HuskSync) and the
    // hooks it feeds run once ALL plugins have finished enabling (see ServerLoadEvent
    // listener below). Bukkit's soft-depend based enable-order sort is best-effort and
    // was observed to NOT reorder Lands ahead of us on servers with large plugin sets
    // (WorldGuard/HuskSync got reordered correctly, Lands didn't) - checking eagerly
    // here in onEnable() intermittently sees Lands as not-yet-enabled even though it
    // is present and will be enabled moments later. ServerLoadEvent is guaranteed to
    // fire only after every plugin's onEnable() has returned, sidestepping that.
    getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
      @org.bukkit.event.EventHandler
      public void onServerLoad(org.bukkit.event.server.ServerLoadEvent event) {
        initializeProtectionIntegrations();
      }
    }, this);

    commandManager = new CommandManager(this);
    commandManager.registerCommands();

    // PVP toggle feature
    if (getConfig().getBoolean("pvp.enabled", true)) {
      pvpStorage = StorageFactory.create(this);
      pvpToggleManager = new PvpToggleManager(this, pvpStorage);
      pvpToggleListener = new PvpToggleListener(this);
      getServer().getPluginManager().registerEvents(pvpToggleListener, this);
      if (Scheduler.isRunningOnCanvas()) {
        try {
          getServer().getPluginManager().registerEvents(
              new com.shyamstudio.celestcombatXtra.listeners.CanvasTeleportListener(this), this);
          getLogger().info("Canvas detected - registered EntityTeleportAsyncEvent listener for PVP re-arm.");
        } catch (Throwable t) {
          getLogger().warning("Canvas detected but failed to register the async teleport listener - "
              + "falling back to PlayerTeleportEvent only for PVP re-arm. Cause: " + t);
        }
      }
      if (getCommand("pvp") != null) {
        getCommand("pvp").setExecutor(new PvpCommand(this));
      }
      if (com.shyamstudio.celestcombatXtra.highlight.PacketEventsBootstrap.isAvailable()) {
        pvpHighlightManager = new PvpHighlightManager(this, pvpToggleManager);
        pvpHighlightManager.start();
      } else {
        getLogger().info("PacketEvents is not available - the PVP status highlight (glow) "
            + "feature is disabled. PVP toggling, warmups, and damage gating are unaffected.");
      }
    } else {
      getLogger().info("PVP toggling is disabled in config (pvp.enabled: false) - /pvp, the status "
          + "highlight, and the toggle-state database are all inactive. PVP damage is unrestricted.");

      teleportGraceManager = new TeleportGraceManager(this);
      getServer().getPluginManager().registerEvents(new TeleportGraceListener(this), this);
      if (Scheduler.isRunningOnCanvas()) {
        try {
          getServer().getPluginManager().registerEvents(
              new com.shyamstudio.celestcombatXtra.listeners.CanvasTeleportGraceListener(this), this);
          getLogger().info("Canvas detected - registered EntityTeleportAsyncEvent listener for teleport "
              + "PVP grace.");
        } catch (Throwable t) {
          getLogger().warning("Canvas detected but failed to register the async teleport listener - "
              + "falling back to PlayerTeleportEvent only for teleport PVP grace. Cause: " + t);
        }
      }
    }

    combatAPI = new CombatAPIImpl(this, combatManager);
    CelestCombatAPI.initialize(combatAPI);

    setupBtatsMetrics();

    long loadTime = System.currentTimeMillis() - startTime;
    getLogger().info("CelestCombat Xtra has been enabled! (Loaded in " + loadTime + "ms)");
  }

  @Override
  public void onDisable() {
    if (combatManager != null) {
      combatManager.shutdown();
    }

    if(combatListeners != null) {
      combatListeners.shutdown();
    }

    if (enderPearlListener != null) {
      enderPearlListener.shutdown();
    }

    if (tridentListener != null) {
      tridentListener.shutdown();
    }

    if (worldGuardHook != null) {
      worldGuardHook.cleanup();
    }

    if (griefPreventionHook != null) {
      griefPreventionHook.cleanup();
    }

    if (landsHook != null) {
      landsHook.cleanup();
    }

    if (huskSyncHook != null) {
      huskSyncHook.cleanup();
    }

    if (killRewardManager != null) {
      killRewardManager.shutdown();
    }

    if (newbieProtectionManager != null) {
      newbieProtectionManager.shutdown();
    }

    if (pvpHighlightManager != null) {
      pvpHighlightManager.shutdown();
    }

    if (pvpToggleManager != null) {
      pvpToggleManager.shutdown();
    }

    if (pvpStorage != null) {
      pvpStorage.shutdown();
    }

    CelestCombatAPI.shutdown();

    getLogger().info("CelestCombat Xtra has been disabled!");
  }

  private void checkProtectionPlugins() {
    hasWorldGuard = isPluginEnabled("WorldGuard") && isWorldGuardAPIAvailable();
    if (hasWorldGuard) {
      getLogger().info("WorldGuard integration enabled successfully!");
    }

    hasGriefPrevention = isPluginEnabled("GriefPrevention") && isGriefPreventionAPIAvailable();
    if (hasGriefPrevention) {
      getLogger().info("GriefPrevention integration enabled successfully!");
    }

    hasLands = isPluginEnabled("Lands") && isLandsAPIAvailable();
    if (hasLands) {
      getLogger().info("Lands integration enabled successfully!");
    }

    hasHuskSync = isPluginEnabled("HuskSync") && isHuskSyncAPIAvailable();
    if (hasHuskSync) {
      getLogger().info("HuskSync integration enabled successfully!");
    }
  }

  private void initializeProtectionIntegrations() {
    checkProtectionPlugins();

    // WorldGuard integration
    if (hasWorldGuard && getConfig().getBoolean("safezone_protection.enabled", true)) {
      worldGuardHook = new WorldGuardHook(this, combatManager);
      getServer().getPluginManager().registerEvents(worldGuardHook, this);
      debug("WorldGuard safezone protection enabled");
    } else if (hasWorldGuard) {
      getLogger().info("Found WorldGuard but safe zone barrier is disabled in config.");
    }

    // Claim system integration (claim_protection) - backed by either GriefPrevention
    // or Lands, whichever is installed / selected via claim_protection.backend.
    // Only one backend is ever active at a time to avoid double barriers/push-back.
    if (getConfig().getBoolean("claim_protection.enabled", true)) {
      String claimBackend = getConfig().getString("claim_protection.backend", "auto").toLowerCase();
      boolean wantsGriefPrevention = "griefprevention".equals(claimBackend)
          || ("auto".equals(claimBackend) && hasGriefPrevention);
      boolean wantsLands = "lands".equals(claimBackend)
          || ("auto".equals(claimBackend) && !hasGriefPrevention);

      if (wantsGriefPrevention && hasGriefPrevention) {
        griefPreventionHook = new GriefPreventionHook(this, combatManager);
        getServer().getPluginManager().registerEvents(griefPreventionHook, this);
        debug("Claim system integration enabled (backend: GriefPrevention)");
      } else if (wantsLands && hasLands) {
        landsHook = new LandsHook(this, combatManager);
        getServer().getPluginManager().registerEvents(landsHook, this);
        debug("Claim system integration enabled (backend: Lands)");
      } else if ("griefprevention".equals(claimBackend) || "lands".equals(claimBackend)) {
        getLogger().warning("claim_protection.backend is set to '" + claimBackend
            + "' but that plugin isn't installed/enabled - claim protection is disabled.");
      } else if (hasGriefPrevention || hasLands) {
        getLogger().warning("Claim protection is enabled but no supported claim plugin was detected.");
      }
    } else if (hasGriefPrevention || hasLands) {
      getLogger().info("Found a supported claim plugin but claim protection is disabled in config.");
    }

    // HuskSync integration - fixes an inventory-duplication exploit on combat-log kill.
    if (hasHuskSync && getConfig().getBoolean("husksync.enabled", true)) {
      huskSyncHook = new HuskSyncHook(this);
      getServer().getPluginManager().registerEvents(huskSyncHook, this);
      combatManager.setHuskSyncHook(huskSyncHook);
      debug("HuskSync integration enabled - combat-log inventory desync fix active");
    } else if (hasHuskSync) {
      getLogger().info("Found HuskSync but the integration is disabled in config (husksync.enabled: false).");
    }
  }

  private boolean isPluginEnabled(String pluginName) {
    Plugin plugin = getServer().getPluginManager().getPlugin(pluginName);
    return plugin != null && plugin.isEnabled();
  }

  private boolean isWorldGuardAPIAvailable() {
    try {
      Class.forName("com.sk89q.worldguard.WorldGuard");
      return WorldGuard.getInstance() != null;
    } catch (ClassNotFoundException | NoClassDefFoundError e) {
      return false;
    }
  }

  private boolean isGriefPreventionAPIAvailable() {
    try {
      Class.forName("me.ryanhamshire.GriefPrevention.GriefPrevention");
      return true;
    } catch (ClassNotFoundException | NoClassDefFoundError e) {
      return false;
    }
  }

  private boolean isLandsAPIAvailable() {
    try {
      Class.forName("me.angeschossen.lands.api.LandsIntegration");
      return true;
    } catch (ClassNotFoundException | NoClassDefFoundError e) {
      return false;
    }
  }

  private boolean isHuskSyncAPIAvailable() {
    try {
      Class.forName("net.william278.husksync.event.BukkitDataSaveEvent");
      return net.william278.husksync.api.BukkitHuskSyncAPI.getInstance() != null;
    } catch (ClassNotFoundException | NoClassDefFoundError e) {
      return false;
    }
  }

  private void setupBtatsMetrics() {
    Scheduler.runTask(() -> {
      try {
        int pluginId = 30372; // https://bstats.org/plugin/bukkit/celestcombatxtra/30372
        new Metrics(this, pluginId);
        if (debugMode) {
          getLogger().info("bStats metrics enabled (ID: 30372). Data appears on bstats.org within ~30 min.");
        }
      } catch (Throwable t) {
        getLogger().warning("Failed to initialize bStats: " + t.getMessage());
      }
    });
  }

  public long getTimeFromConfig(String path, String defaultValue) {
    return timeFormatter.getTimeFromConfig(path, defaultValue);
  }

  public long getTimeFromConfigInMilliseconds(String path, String defaultValue) {
    long ticks = timeFormatter.getTimeFromConfig(path, defaultValue);
    return ticks * 50L; // Convert ticks to milliseconds
  }

  public void refreshTimeCache() {
    if (timeFormatter != null) {
      timeFormatter.clearCache();
    }
  }

  public void debug(String message) {
    if (debugMode) {
      getLogger().info("[DEBUG] " + message);
    }
  }

  public boolean isActionBarDisabled() {
    return getConfig().getBoolean("disable_actionbar", false);
  }

  public void sendActionBar(Player player, Component component) {
    if (isActionBarDisabled() || player == null) return;
    player.sendActionBar(component);
  }

  public void reload() {
    if (worldGuardHook != null) {
      worldGuardHook.cleanup();
    }

    if (griefPreventionHook != null) {
      griefPreventionHook.cleanup();
    }

    if (landsHook != null) {
      landsHook.cleanup();
    }

    if (huskSyncHook != null) {
      huskSyncHook.cleanup();
    }
  }
}