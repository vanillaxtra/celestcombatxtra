package com.shyamstudio.celestcombatXtra;

import com.shyamstudio.celestcombatXtra.api.CelestCombatAPI;
import com.shyamstudio.celestcombatXtra.api.CombatAPIImpl;
import org.bstats.bukkit.Metrics;
import com.shyamstudio.celestcombatXtra.combat.CombatManager;
import com.shyamstudio.celestcombatXtra.combat.DeathAnimationManager;
import com.shyamstudio.celestcombatXtra.commands.CommandManager;
import com.shyamstudio.celestcombatXtra.configs.TimeFormatter;
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
import com.shyamstudio.celestcombatXtra.listeners.TridentListener;
import com.shyamstudio.celestcombatXtra.protection.NewbieProtectionManager;
import com.shyamstudio.celestcombatXtra.rewards.KillRewardManager;
import com.shyamstudio.celestcombatXtra.updates.ConfigUpdater;
import com.shyamstudio.celestcombatXtra.updates.LanguageUpdater;
import com.shyamstudio.celestcombatXtra.updates.UpdateChecker;
import com.sk89q.worldguard.WorldGuard;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import lombok.Getter;
import lombok.experimental.Accessors;
import org.bukkit.Bukkit;
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
    // ports old configs onto the new commented template and writes a backup when needed
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

    // soft-depends can enable after us; wait until every plugin has finished onEnable
    getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
      @org.bukkit.event.EventHandler
      public void onServerLoad(org.bukkit.event.server.ServerLoadEvent event) {
        initializeProtectionIntegrations();
      }
    }, this);

    commandManager = new CommandManager(this);
    commandManager.registerCommands();

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

    if (hasWorldGuard && getConfig().getBoolean("safezone_protection.enabled", true)) {
      worldGuardHook = new WorldGuardHook(this, combatManager);
      getServer().getPluginManager().registerEvents(worldGuardHook, this);
      debug("WorldGuard safezone protection enabled");
    } else if (hasWorldGuard) {
      getLogger().info("Found WorldGuard but safe zone barrier is disabled in config.");
    }

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

  public void sendActionBar(Player player, BaseComponent... components) {
    if (isActionBarDisabled() || player == null) return;
    player.spigot().sendMessage(ChatMessageType.ACTION_BAR, components);
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