package com.shyamstudio.celestcombatXtra.commands.subcommands;

import com.shyamstudio.celestcombatXtra.CelestCombatPro;
import com.shyamstudio.celestcombatXtra.CelestCombatXtra;
import com.shyamstudio.celestcombatXtra.commands.BaseCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class ConfigCommand extends BaseCommand {

    public ConfigCommand(CelestCombatPro plugin) {
        super(plugin);
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!checkSender(sender)) {
            return true;
        }

        FileConfiguration config = plugin.getConfig();

        if (args.length == 0) {
            String topKeys = String.join(", ", config.getKeys(false));
            messageService.sendMessage(sender, "config_section_children", Map.of(
                    "path", "root",
                    "children", topKeys.isEmpty() ? "-" : topKeys
            ));
            return true;
        }

        ResolveResult exact = resolvePath(config, Arrays.asList(args));
        if (exact.exists()) {
            if (exact.isSection()) {
                String children = String.join(", ", exact.getSection().getKeys(false));
                messageService.sendMessage(sender, "config_section_children", Map.of(
                        "path", exact.path(),
                        "children", children.isEmpty() ? "-" : children
                ));
            } else {
                Object value = config.get(exact.path());
                messageService.sendMessage(sender, "config_key_info", Map.of(
                        "path", exact.path(),
                        "type", describeType(value),
                        "value", String.valueOf(value)
                ));
            }
            return true;
        }

        // Try set mode: last argument is new value, preceding args are config path.
        if (args.length < 2) {
            messageService.sendMessage(sender, "config_invalid_path", Map.of("path", String.join(".", args)));
            return true;
        }

        String rawValue = args[args.length - 1];
        List<String> pathTokens = Arrays.asList(args).subList(0, args.length - 1);
        ResolveResult keyResult = resolvePath(config, pathTokens);

        if (!keyResult.exists() || keyResult.isSection()) {
            messageService.sendMessage(sender, "config_invalid_path", Map.of("path", String.join(".", pathTokens)));
            return true;
        }

        String path = keyResult.path();
        Object currentValue = config.get(path);
        ParseResult parseResult = parseByCurrentType(currentValue, rawValue);
        if (!parseResult.success()) {
            messageService.sendMessage(sender, "config_set_type_error", Map.of(
                    "path", path,
                    "type", describeType(currentValue),
                    "input", rawValue
            ));
            return true;
        }

        config.set(path, parseResult.value());
        plugin.saveConfig();
        reloadAllSystems(sender);

        messageService.sendMessage(sender, "config_set_success", Map.of(
                "path", path,
                "value", String.valueOf(parseResult.value())
        ));
        messageService.sendMessage(sender, "config_reload_success");
        return true;
    }

    @Override
    public String getPermission() {
        return "celestcombatxtra.command.config";
    }

    @Override
    public boolean isPlayerOnly() {
        return false;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (!hasPermission(sender)) {
            return Collections.emptyList();
        }

        FileConfiguration config = plugin.getConfig();
        if (args.length == 0) {
            return new ArrayList<>(config.getKeys(false));
        }

        String currentInput = args[args.length - 1];
        List<String> fixedTokens = Arrays.asList(args).subList(0, args.length - 1);
        ResolveResult fixed = resolvePath(config, fixedTokens);

        // If the fixed tokens resolve to a section, suggest child keys.
        if (fixed.exists() && fixed.isSection()) {
            return fixed.getSection().getKeys(false).stream()
                    .filter(k -> k.toLowerCase(Locale.ROOT).startsWith(currentInput.toLowerCase(Locale.ROOT)))
                    .sorted()
                    .collect(Collectors.toList());
        }

        // If fixed tokens resolve to a key, suggest typed value options.
        if (fixed.exists() && !fixed.isSection()) {
            Object value = config.get(fixed.path());
            return suggestValues(value, currentInput);
        }

        // Root-level key suggestions when fixed path is empty/invalid partial.
        if (fixedTokens.isEmpty()) {
            return config.getKeys(false).stream()
                    .filter(k -> k.toLowerCase(Locale.ROOT).startsWith(currentInput.toLowerCase(Locale.ROOT)))
                    .sorted()
                    .collect(Collectors.toList());
        }

        return Collections.emptyList();
    }

    private void reloadAllSystems(CommandSender sender) {
        plugin.reload();
        plugin.reloadConfig();
        plugin.getLanguageManager().reloadLanguages();
        plugin.refreshTimeCache();

        if (plugin.getWorldGuardHook() != null) {
            plugin.getWorldGuardHook().reloadConfig();
        }
        if (plugin.getGriefPreventionHook() != null) {
            plugin.getGriefPreventionHook().reloadConfig();
        }

        plugin.getCombatManager().reloadConfig();
        plugin.getKillRewardManager().loadConfig();
        plugin.getNewbieProtectionManager().reloadConfig();
        plugin.getCombatListeners().reload();

        if (plugin instanceof CelestCombatXtra xtra) {
            List<String> skippedReserved = xtra.reloadPhase1Listeners();
            if (!skippedReserved.isEmpty() && sender != null) {
                Map<String, String> skipPh = new HashMap<>();
                skipPh.put("materials", String.join(", ", skippedReserved));
                messageService.sendMessage(sender, "config_reload_skipped_reserved_cooldown_items", skipPh);
            }
        }

        messageService.clearKeyExistsCache();
    }

    private ResolveResult resolvePath(FileConfiguration config, List<String> tokens) {
        String path = "";
        ConfigurationSection currentSection = config;

        if (tokens.isEmpty()) {
            return new ResolveResult(true, true, "root", config);
        }

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            path = path.isEmpty() ? token : path + "." + token;

            if (!config.contains(path)) {
                return ResolveResult.notFound(path);
            }

            boolean isLast = i == tokens.size() - 1;
            if (config.isConfigurationSection(path)) {
                currentSection = config.getConfigurationSection(path);
                if (currentSection == null) {
                    return ResolveResult.notFound(path);
                }
                if (isLast) {
                    return new ResolveResult(true, true, path, currentSection);
                }
            } else if (isLast) {
                return new ResolveResult(true, false, path, null);
            } else {
                // Encountered value before path ended.
                return ResolveResult.notFound(path);
            }
        }

        return ResolveResult.notFound(path);
    }

    private List<String> suggestValues(Object currentValue, String input) {
        if (currentValue instanceof Boolean) {
            return Arrays.asList("true", "false").stream()
                    .filter(v -> v.startsWith(input.toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        if (currentValue instanceof Integer || currentValue instanceof Long) {
            String value = String.valueOf(currentValue);
            return value.startsWith(input) ? List.of(value) : Collections.emptyList();
        }
        if (currentValue instanceof Double || currentValue instanceof Float) {
            String value = String.valueOf(currentValue);
            return value.startsWith(input) ? List.of(value) : Collections.emptyList();
        }
        if (currentValue instanceof List<?>) {
            return Collections.singletonList(String.valueOf(currentValue));
        }
        if (currentValue != null) {
            String value = String.valueOf(currentValue);
            return value.toLowerCase(Locale.ROOT).startsWith(input.toLowerCase(Locale.ROOT))
                    ? List.of(value)
                    : Collections.emptyList();
        }
        return Collections.emptyList();
    }

    private ParseResult parseByCurrentType(Object currentValue, String rawInput) {
        if (currentValue instanceof Boolean) {
            if (!rawInput.equalsIgnoreCase("true") && !rawInput.equalsIgnoreCase("false")) {
                return ParseResult.failure();
            }
            return ParseResult.success(Boolean.parseBoolean(rawInput));
        }

        if (currentValue instanceof Integer) {
            try {
                return ParseResult.success(Integer.parseInt(rawInput));
            } catch (NumberFormatException ignored) {
                return ParseResult.failure();
            }
        }

        if (currentValue instanceof Long) {
            try {
                return ParseResult.success(Long.parseLong(rawInput));
            } catch (NumberFormatException ignored) {
                return ParseResult.failure();
            }
        }

        if (currentValue instanceof Double) {
            try {
                return ParseResult.success(Double.parseDouble(rawInput));
            } catch (NumberFormatException ignored) {
                return ParseResult.failure();
            }
        }

        if (currentValue instanceof Float) {
            try {
                return ParseResult.success(Float.parseFloat(rawInput));
            } catch (NumberFormatException ignored) {
                return ParseResult.failure();
            }
        }

        if (currentValue instanceof List<?>) {
            // comma-separated list format
            List<String> list = Arrays.stream(rawInput.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
            return ParseResult.success(list);
        }

        // Fallback to string for unknown/nullable types
        return ParseResult.success(rawInput);
    }

    private String describeType(Object value) {
        if (value == null) return "unknown";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Integer) return "int";
        if (value instanceof Long) return "long";
        if (value instanceof Double) return "double";
        if (value instanceof Float) return "float";
        if (value instanceof List<?>) return "list";
        return "string";
    }

    private record ResolveResult(boolean exists, boolean section, String path, ConfigurationSection sectionRef) {
        static ResolveResult notFound(String path) {
            return new ResolveResult(false, false, path, null);
        }

        boolean isSection() {
            return section;
        }

        ConfigurationSection getSection() {
            return Objects.requireNonNull(sectionRef);
        }
    }

    private record ParseResult(boolean success, Object value) {
        static ParseResult success(Object value) {
            return new ParseResult(true, value);
        }

        static ParseResult failure() {
            return new ParseResult(false, null);
        }
    }
}

