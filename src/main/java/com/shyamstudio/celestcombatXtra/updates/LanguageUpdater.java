package com.shyamstudio.celestcombatXtra.updates;

import lombok.Getter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

public class LanguageUpdater {
    private final String currentVersion;
    private final JavaPlugin plugin;
    private static final String LANGUAGE_VERSION_KEY = "language_version";
    private static final List<String> SUPPORTED_LANGUAGES = Arrays.asList("en_US", "vi_VN");

    private final Set<LanguageFileType> activeFileTypes = new HashSet<>();

    public LanguageUpdater(JavaPlugin plugin) {
        this(plugin, LanguageFileType.MESSAGES);
    }

    public LanguageUpdater(JavaPlugin plugin, LanguageFileType... fileTypes) {
        this.plugin = plugin;
        this.currentVersion = plugin.getDescription().getVersion();
        activeFileTypes.addAll(Arrays.asList(fileTypes));
    }

    @Getter
    public enum LanguageFileType {
        MESSAGES("messages.yml");

        private final String fileName;

        LanguageFileType(String fileName) {
            this.fileName = fileName;
        }
    }

    /**
     * Check and update all language files for all supported languages
     */
    public void checkAndUpdateLanguageFiles() {
        for (String language : SUPPORTED_LANGUAGES) {
            File langDir = new File(plugin.getDataFolder(), "language/" + language);
            if (!langDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                langDir.mkdirs();
            }

            for (LanguageFileType fileType : activeFileTypes) {
                String resourcePath = "language/" + language + "/" + fileType.getFileName();
                if (plugin.getResource(resourcePath) == null) {
                    plugin.getLogger().fine("Skipping " + resourcePath + " — no default resource packaged");
                    continue;
                }
                File languageFile = new File(langDir, fileType.getFileName());
                updateLanguageFile(language, languageFile, fileType);
            }
        }
    }

    private void updateLanguageFile(String language, File languageFile, LanguageFileType fileType) {
        try {
            if (!languageFile.getParentFile().exists()) {
                //noinspection ResultOfMethodCallIgnored
                languageFile.getParentFile().mkdirs();
            }

            if (!languageFile.exists()) {
                writeDefaultLanguageFile(language, languageFile, fileType);
                plugin.getLogger().info("Created new " + fileType.getFileName() + " for " + language);
                return;
            }

            FileConfiguration currentConfig = YamlConfiguration.loadConfiguration(languageFile);
            String configVersionStr = currentConfig.getString(LANGUAGE_VERSION_KEY, "0.0.0");
            Version configVersion = new Version(configVersionStr);
            Version pluginVersion = new Version(currentVersion);

            if (configVersion.compareTo(pluginVersion) >= 0) {
                return;
            }

            if (!configVersionStr.equals("0.0.0")) {
                plugin.getLogger().info("Updating " + language + " " + fileType.getFileName() +
                        " from version " + configVersionStr + " to " + currentVersion);
            }

            Map<String, Object> userValues = flattenConfig(currentConfig);
            userValues.remove(LANGUAGE_VERSION_KEY);

            List<String> templateLines = loadDefaultLanguageLines(language, fileType);
            if (templateLines == null) {
                plugin.getLogger().warning("Default " + fileType.getFileName() + " for " + language +
                        " not found in the plugin's resources.");
                return;
            }

            File tempFile = new File(plugin.getDataFolder(),
                    "language/" + language + "/" + fileType.getFileName().replace(".yml", "_new.yml"));
            writeLines(tempFile, withVersionHeader(templateLines));
            FileConfiguration newConfig = YamlConfiguration.loadConfiguration(tempFile);

            boolean configDiffers = hasConfigDifferences(userValues, newConfig);

            if (configDiffers) {
                File backupFile = new File(plugin.getDataFolder(),
                        "language/" + language + "/" + fileType.getFileName().replace(".yml", "_backup_" + configVersionStr + ".yml"));
                Files.copy(languageFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                plugin.getLogger().info(language + " " + fileType.getFileName() + " backup created at " + backupFile.getName());
            } else if (!configVersionStr.equals("0.0.0")) {
                plugin.getLogger().info("No significant changes detected in " + language + " " +
                        fileType.getFileName() + ", skipping backup creation");
            }

            List<String> merged = ConfigTextMerger.merge(withVersionHeader(templateLines), userValues);
            writeLines(languageFile, merged);
            //noinspection ResultOfMethodCallIgnored
            tempFile.delete();

        } catch (Exception e) {
            plugin.getLogger().severe("Failed to update " + language + " " + fileType.getFileName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void writeDefaultLanguageFile(String language, File destinationFile, LanguageFileType fileType) throws IOException {
        List<String> templateLines = loadDefaultLanguageLines(language, fileType);
        if (templateLines == null) {
            plugin.getLogger().warning("Default " + fileType.getFileName() + " for " + language +
                    " not found in the plugin's resources.");
            //noinspection ResultOfMethodCallIgnored
            destinationFile.getParentFile().mkdirs();
            YamlConfiguration emptyConfig = new YamlConfiguration();
            emptyConfig.set(LANGUAGE_VERSION_KEY, currentVersion);
            emptyConfig.set("_note", "This is an empty " + fileType.getFileName() +
                    " created because no default was found in the plugin resources.");
            emptyConfig.save(destinationFile);
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        destinationFile.getParentFile().mkdirs();
        writeLines(destinationFile, withVersionHeader(templateLines));
    }

    private List<String> loadDefaultLanguageLines(String language, LanguageFileType fileType) throws IOException {
        try (InputStream in = plugin.getResource("language/" + language + "/" + fileType.getFileName())) {
            if (in == null) return null;
            return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .lines()
                    .toList();
        }
    }

    private List<String> withVersionHeader(List<String> defaultLines) {
        List<String> newLines = new ArrayList<>(defaultLines.size() + 3);
        newLines.add("# Language file version - Do not modify this value");
        newLines.add(LANGUAGE_VERSION_KEY + ": " + currentVersion);
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
            if (path.equals(LANGUAGE_VERSION_KEY)) continue;

            if (!newConfig.contains(path)) {
                return true;
            }

            Object newDefaultValue = newConfig.get(path);
            if (newDefaultValue != null && !newDefaultValue.equals(oldValue)) {
                return true;
            }
        }

        for (String path : newConfigMap.keySet()) {
            if (!path.equals(LANGUAGE_VERSION_KEY) && !userValues.containsKey(path)) {
                return true;
            }
        }

        return false;
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
