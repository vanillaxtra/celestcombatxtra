package com.shyamstudio.celestcombatXtra.language;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class MessageService {
    private final JavaPlugin plugin;
    private final LanguageManager languageManager;

    private static final Map<String, String> EMPTY_PLACEHOLDERS = Collections.emptyMap();

    // ticks * 50ms, matching the previous sendTitle(String,String,10,70,20) call
    private static final Title.Times TITLE_TIMES = Title.Times.times(
            Duration.ofMillis(500), Duration.ofMillis(3500), Duration.ofMillis(1000));

    // Cache for key existence checks to reduce repeated lookups
    private final Map<String, Boolean> keyExistsCache = new ConcurrentHashMap<>(128);

    /**
     * Sends a message to a CommandSender with no placeholders
     * @param sender The command sender to receive the message
     * @param key The message key
     */
    public void sendMessage(CommandSender sender, String key) {
        sendMessage(sender, key, EMPTY_PLACEHOLDERS);
    }

    /**
     * Sends a message to a Player with no placeholders
     * @param player The player to receive the message
     * @param key The message key
     */
    public void sendMessage(Player player, String key) {
        sendMessage(player, key, EMPTY_PLACEHOLDERS);
    }

    /**
     * Sends a message to a Player with placeholders
     * @param player The player to receive the message
     * @param key The message key
     * @param placeholders Map of placeholders to replace in the message
     */
    public void sendMessage(Player player, String key, Map<String, String> placeholders) {
        // Use the CommandSender version but with player-specific features
        sendMessage((CommandSender) player, key, placeholders);
    }

    /**
     * Sends a message to a CommandSender with placeholders
     * @param sender The command sender to receive the message
     * @param key The message key
     * @param placeholders Map of placeholders to replace in the message
     */
    public void sendMessage(CommandSender sender, String key, Map<String, String> placeholders) {
        // Validate the message key exists (using cache to avoid lookups)
        if (!checkKeyExists(key)) {
            plugin.getLogger().warning("Message key not found: " + key);
            sender.sendMessage(Component.text("Missing message key: " + key, NamedTextColor.RED));
            return;
        }

        // Get and send the chat message if it exists
        Component message = languageManager.getMessage(key, placeholders);
        if (message != null) {
            sender.sendMessage(message);
        }

        // Process player-specific features
        if (sender instanceof Player player) {
            sendPlayerSpecificContent(player, key, placeholders);
        }
    }

    /**
     * Check if a key exists, using cache for efficiency
     * @param key The message key to check
     * @return true if the key exists, false otherwise
     */
    private boolean checkKeyExists(String key) {
        return keyExistsCache.computeIfAbsent(key, languageManager::keyExists);
    }

    /**
     * Clear the key existence cache (used during reloads)
     */
    public void clearKeyExistsCache() {
        keyExistsCache.clear();
    }

    /**
     * Sends a message to the console with no placeholders
     * @param key The message key
     */
    public void sendConsoleMessage(String key) {
        sendConsoleMessage(key, EMPTY_PLACEHOLDERS);
    }

    /**
     * Sends a message to the console with placeholders
     * @param key The message key
     * @param placeholders Map of placeholders to replace in the message
     */
    public void sendConsoleMessage(String key, Map<String, String> placeholders) {
        // Validate the message key exists
        if (!languageManager.keyExists(key)) {
            plugin.getLogger().warning("Message key not found: " + key);
            plugin.getLogger().warning("Missing message key: " + key);
            return;
        }

        // Get the raw message without prefix for console formatting
        Component message = languageManager.getRawMessage(key, placeholders);
        if (message != null) {
            plugin.getLogger().info(ColorUtil.plainOf(message));
        }
    }

    /**
     * Handles player-specific message components (title, subtitle, action bar, sound)
     * @param player The player to receive the content
     * @param key The message key
     * @param placeholders Map of placeholders to replace in the content
     */
    private void sendPlayerSpecificContent(Player player, String key, Map<String, String> placeholders) {
        // Title and subtitle
        Component title = languageManager.getTitle(key, placeholders);
        Component subtitle = languageManager.getSubtitle(key, placeholders);
        if (title != null || subtitle != null) {
            player.showTitle(Title.title(
                    title != null ? title : Component.empty(),
                    subtitle != null ? subtitle : Component.empty(),
                    TITLE_TIMES
            ));
        }

        // Action bar
        boolean skipActionBar = plugin instanceof CelestCombatPro ccp && ccp.isActionBarDisabled();
        if (!skipActionBar) {
            Component actionBar = languageManager.getActionBar(key, placeholders);
            if (actionBar != null) {
                player.sendActionBar(actionBar);
            }
        }

        // Sound
        String soundName = languageManager.getSound(key);
        if (soundName != null) {
            try {
                player.playSound(player.getLocation(), soundName, 1.0f, 1.0f);
            } catch (Exception e) {
                plugin.getLogger().warning("Invalid sound name for key " + key + ": " + soundName);
            }
        }
    }
}
