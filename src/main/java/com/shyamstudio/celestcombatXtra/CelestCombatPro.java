package com.shyamstudio.celestcombatXtra;

import com.shyamstudio.celestcombatXtra.api.CelestCombatAPI;
import com.shyamstudio.celestcombatXtra.api.CombatAPIImpl;
import com.shyamstudio.celestcombatXtra.bstats.Metrics;
import com.shyamstudio.celestcombatXtra.combat.CombatManager;
import com.shyamstudio.celestcombatXtra.combat.DeathAnimationManager;
import com.shyamstudio.celestcombatXtra.commands.CommandManager;
import com.shyamstudio.celestcombatXtra.configs.TimeFormatter;
import com.shyamstudio.celestcombatXtra.hooks.protection.GriefPreventionHook;
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

import lombok.Getter;
import lombok.experimental.Accessors;
import org.bukkit.Bukkit;
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
  private CombatAPIImpl combatAPI;

  public static boolean hasWorldGuard = false;
  public static boolean hasGriefPrevention = false;

  @Override
  public void onEnable() {
    long startTime = System.currentTimeMillis();
    instance = this;

    saveDefaultConfig();
    checkProtectionPlugins();

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

    // WorldGuard integration
    if (hasWorldGuard && getConfig().getBoolean("safezone_protection.enabled", true)) {
      worldGuardHook = new WorldGuardHook(this, combatManager);
      getServer().getPluginManager().registerEvents(worldGuardHook, this);
      debug("WorldGuard safezone protection enabled");
    } else if(hasWorldGuard) {
      getLogger().info("Found WorldGuard but safe zone barrier is disabled in config.");
    }

    // GriefPrevention integration
    if (hasGriefPrevention && getConfig().getBoolean("claim_protection.enabled", true)) {
      griefPreventionHook = new GriefPreventionHook(this, combatManager);
      getServer().getPluginManager().registerEvents(griefPreventionHook, this);
      debug("GriefPrevention claim protection enabled");
    } else if(hasGriefPrevention) {
      getLogger().info("Found GriefPrevention but claim protection is disabled in config.");
    }

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

  private void setupBtatsMetrics() {
    Scheduler.runTask(() -> {
      Metrics metrics = new Metrics(this, 27299);
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

  public void reload() {
    if (worldGuardHook != null) {
      worldGuardHook.cleanup();
    }

    if (griefPreventionHook != null) {
      griefPreventionHook.cleanup();
    }
  }
}