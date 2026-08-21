package com.shyamstudio.celestcombatXtra.language;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Map;

public class ColorUtil {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    /**
     * Parses MiniMessage source text into a Component. Placeholders are resolved as
     * unparsed literals (Placeholder.unparsed), so placeholder values can never be
     * interpreted as MiniMessage tags themselves.
     */
    public static Component parse(String miniMessageSource, Map<String, String> placeholders) {
        if (miniMessageSource == null) return Component.empty();

        if (placeholders == null || placeholders.isEmpty()) {
            return MINI_MESSAGE.deserialize(miniMessageSource);
        }

        TagResolver[] resolvers = placeholders.entrySet().stream()
                .map(entry -> Placeholder.unparsed(entry.getKey(), entry.getValue() != null ? entry.getValue() : ""))
                .toArray(TagResolver[]::new);

        return MINI_MESSAGE.deserialize(miniMessageSource, resolvers);
    }

    public static Component parse(String miniMessageSource) {
        return parse(miniMessageSource, null);
    }

    /**
     * Serializes a Component back to a legacy '§'-coded string, for the few remaining
     * consumers that require a legacy String (unused GUI/item lore paths, log fallback).
     */
    public static String legacyOf(Component component) {
        if (component == null) return "";
        return LegacyComponentSerializer.legacySection().serialize(component);
    }

    /**
     * Serializes a Component to plain text with all formatting stripped, for console output.
     */
    public static String plainOf(Component component) {
        if (component == null) return "";
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
