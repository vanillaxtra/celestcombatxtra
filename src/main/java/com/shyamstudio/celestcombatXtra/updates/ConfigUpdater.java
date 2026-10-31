package com.shyamstudio.celestcombatXtra.updates;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

public class ConfigUpdater {
    private final String currentVersion;
    private final CelestCombatPro plugin;
    private static final String CONFIG_VERSION_KEY = "config_version";

    public ConfigUpdater(CelestCombatPro plugin) {
        this.plugin = plugin;
        this.currentVersion = plugin.getDescription().getVersion();
    }

    /**
     * Check if the config needs to be updated and update it if necessary
     */
    public void checkAndUpdateConfig() {
        File configFile = new File(plugin.getDataFolder(), "config.yml");

        if (!configFile.exists()) {
            writeDefaultConfig(configFile);
            return;
        }

        FileConfiguration currentConfig = YamlConfiguration.loadConfiguration(configFile);
        String configVersionStr = currentConfig.getString(CONFIG_VERSION_KEY, "0.0.0");
        Version configVersion = new Version(configVersionStr);
        Version pluginVersion = new Version(currentVersion);

        if (configVersion.compareTo(pluginVersion) >= 0) {
            return;
        }

        plugin.getLogger().info("Updating config from version " + configVersionStr + " to " + currentVersion);

        try {
            Map<String, Object> userValues = flattenConfig(currentConfig);
            userValues.remove(CONFIG_VERSION_KEY);

            List<String> templateLines = loadDefaultTemplateLines();
            if (templateLines == null) {
                plugin.getLogger().severe("Default config.yml not found in the plugin's resources.");
                return;
            }

            // parse defaults for difference check only
            File tempFile = new File(plugin.getDataFolder(), "config_new.yml");
            writeLines(tempFile, withVersionHeader(templateLines));
            FileConfiguration newConfig = YamlConfiguration.loadConfiguration(tempFile);

            boolean configDiffers = hasConfigDifferences(userValues, newConfig);

            if (configDiffers) {
                File backupFile = new File(plugin.getDataFolder(), "config_backup_" + configVersionStr + ".yml");
                Files.copy(configFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                plugin.getLogger().info("Config backup created at " + backupFile.getName());
            } else {
                plugin.debug("No significant config changes detected, skipping backup creation");
            }

            logRemovedPaths(userValues, newConfig);

            List<String> merged = ConfigTextMerger.merge(withVersionHeader(templateLines), userValues);
            writeLines(configFile, merged);
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();
            plugin.reloadConfig();
            plugin.getLogger().info("Config updated to version " + currentVersion);

        } catch (Exception e) {
            plugin.getLogger().severe("Failed to update config: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void writeDefaultConfig(File destinationFile) {
        try {
            List<String> templateLines = loadDefaultTemplateLines();
            if (templateLines == null) {
                plugin.getLogger().warning("Default config.yml not found in the plugin's resources.");
                //noinspection ResultOfMethodCallIgnored
                destinationFile.createNewFile();
                return;
            }
            writeLines(destinationFile, withVersionHeader(templateLines));
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to create default config with header: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private List<String> loadDefaultTemplateLines() throws IOException {
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in == null) return null;
            return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .lines()
                    .toList();
        }
    }

    private List<String> withVersionHeader(List<String> defaultLines) {
        List<String> newLines = new ArrayList<>(defaultLines.size() + 3);
        newLines.add("# Configuration version - Do not modify this value");
        newLines.add(CONFIG_VERSION_KEY + ": " + currentVersion);
        newLines.add("");
        newLines.addAll(defaultLines);
        return newLines;
    }

    private static void writeLines(File file, List<String> lines) throws IOException {
        Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
    }

    private boolean hasConfigDifferences(Map<String, Object> userValues, FileConfiguration newConfig) {
        Map<String, Object> newConfigMap = flattenConfig(newConfig);

        for (Map.Entry<String, Object> entry : userValues.entrySet()) {
            String path = entry.getKey();
            Object oldValue = entry.getValue();
            if (path.equals(CONFIG_VERSION_KEY)) continue;

            if (!newConfig.contains(path)) {
                return true;
            }

            Object newDefaultValue = newConfig.get(path);
            if (newDefaultValue != null && !newDefaultValue.equals(oldValue)) {
                return true;
            }
        }

        for (String path : newConfigMap.keySet()) {
            if (!path.equals(CONFIG_VERSION_KEY) && !userValues.containsKey(path)) {
                return true;
            }
        }

        return false;
    }

    private void logRemovedPaths(Map<String, Object> userValues, FileConfiguration newConfig) {
        for (String path : userValues.keySet()) {
            if (path.equals(CONFIG_VERSION_KEY)) continue;
            if (!newConfig.contains(path)) {
                plugin.getLogger().warning("Config path '" + path + "' from old config no longer exists in new config");
            }
        }
    }

    private Map<String, Object> flattenConfig(ConfigurationSection config) {
        Map<String, Object> result = new HashMap<>();
        for (String key : config.getKeys(true)) {
            if (!config.isConfigurationSection(key)) {
                result.put(key, config.get(key));
            }
        }
        return result;
    }
}
