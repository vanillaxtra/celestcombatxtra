package com.shyamstudio.celestcombatXtra.updates;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Merges user config values into a commented YAML template without stripping comments.
 * Walks template lines, tracks the current key path via indent, and patches matching values in place.
 */
final class ConfigTextMerger {
    private static final Pattern KEY_LINE = Pattern.compile("^(\\s*)([^:#\\s][^:]*)\\s*:(.*)$");

    private ConfigTextMerger() {
    }

    static List<String> merge(List<String> templateLines, Map<String, Object> userValues) {
        List<String> out = new ArrayList<>(templateLines.size());
        Deque<Frame> stack = new ArrayDeque<>();
        int i = 0;
        while (i < templateLines.size()) {
            String line = templateLines.get(i);
            String trimmed = line.trim();

            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                out.add(line);
                i++;
                continue;
            }

            Matcher m = KEY_LINE.matcher(line);
            if (!m.matches()) {
                out.add(line);
                i++;
                continue;
            }

            int indent = m.group(1).length();
            String key = m.group(2).trim();
            String rest = m.group(3);

            while (!stack.isEmpty() && stack.peek().indent >= indent) {
                stack.pop();
            }
            String path = stack.isEmpty() ? key : stack.peek().path + "." + key;
            stack.push(new Frame(indent, path));

            if (!userValues.containsKey(path)) {
                out.add(line);
                i++;
                continue;
            }

            Object userValue = userValues.get(path);

            if (userValue instanceof Collection<?> list) {
                out.add(m.group(1) + key + ":");
                String itemIndent = " ".repeat(indent + 2);
                for (Object item : list) {
                    emitListItem(out, itemIndent, item);
                }
                i = skipValueBlock(templateLines, i, indent);
                continue;
            }

            if (userValue instanceof Map<?, ?> map) {
                out.add(m.group(1) + key + ":");
                String childIndent = " ".repeat(indent + 2);
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    out.add(childIndent + String.valueOf(e.getKey()) + ": " + formatScalar(e.getValue()));
                }
                i = skipValueBlock(templateLines, i, indent);
                continue;
            }

            String scalarText = formatScalar(userValue);
            if (scalarText.contains("\n")) {
                out.add(m.group(1) + key + ": |");
                String bodyIndent = " ".repeat(indent + 2);
                for (String bodyLine : scalarText.split("\n", -1)) {
                    out.add(bodyIndent + bodyLine);
                }
                i = skipValueBlock(templateLines, i, indent);
                continue;
            }

            String inlineComment = extractInlineComment(rest);
            out.add(m.group(1) + key + ": " + scalarText + inlineComment);
            i++;
        }
        return out;
    }

    private static void emitListItem(List<String> out, String itemIndent, Object item) {
        if (item instanceof Map<?, ?> map) {
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String k = String.valueOf(e.getKey());
                String v = formatScalar(e.getValue());
                if (first) {
                    out.add(itemIndent + "- " + k + ": " + v);
                    first = false;
                } else {
                    out.add(itemIndent + "  " + k + ": " + v);
                }
            }
            if (first) {
                out.add(itemIndent + "- {}");
            }
            return;
        }
        out.add(itemIndent + "- " + formatScalar(item));
    }

    /**
     * Skip the current key's value block (list items / nested keys / folded scalar body).
     * Caller has already consumed the key line at index {@code keyLineIndex}.
     */
    private static int skipValueBlock(List<String> lines, int keyLineIndex, int keyIndent) {
        int i = keyLineIndex + 1;
        while (i < lines.size()) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                // stop if next real content is a sibling/parent key
                int j = i + 1;
                while (j < lines.size()) {
                    String peek = lines.get(j).trim();
                    if (!peek.isEmpty() && !peek.startsWith("#")) break;
                    j++;
                }
                if (j >= lines.size()) {
                    return lines.size();
                }
                int nextIndent = leadingSpaces(lines.get(j));
                if (nextIndent <= keyIndent && KEY_LINE.matcher(lines.get(j)).matches()) {
                    return i;
                }
                i++;
                continue;
            }
            int indent = leadingSpaces(line);
            if (indent <= keyIndent) {
                return i;
            }
            i++;
        }
        return i;
    }

    private static String extractInlineComment(String rest) {
        if (rest == null) return "";
        String s = rest.trim();
        if (s.isEmpty()) return "";
        // strip value, keep trailing comment
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle) inDouble = !inDouble;
            else if (c == '#' && !inSingle && !inDouble) {
                return " " + s.substring(i);
            }
        }
        return "";
    }

    static String formatScalar(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        String s = String.valueOf(value);
        if (s.isEmpty()) return "\"\"";
        if (needsQuotes(s)) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return s;
    }

    private static boolean needsQuotes(String s) {
        if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)
                || "null".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s)
                || "no".equalsIgnoreCase(s)) {
            return true;
        }
        if (Character.isDigit(s.charAt(0)) || s.charAt(0) == '-' || s.charAt(0) == '+') {
            try {
                Double.parseDouble(s);
                return true;
            } catch (NumberFormatException ignored) {
                // not a number
            }
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ':' || c == '#' || c == '{' || c == '}' || c == '[' || c == ']'
                    || c == ',' || c == '&' || c == '*' || c == '!' || c == '|' || c == '>'
                    || c == '\'' || c == '"' || c == '%' || c == '@' || c == '`') {
                return true;
            }
            if (Character.isWhitespace(c)) return true;
        }
        return false;
    }

    private static int leadingSpaces(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') n++;
        return n;
    }

    private record Frame(int indent, String path) {
    }

    // runnable check: java ...ConfigTextMerger
    public static void main(String[] args) {
        List<String> template = List.of(
                "# keep me",
                "combat:",
                "  duration: 30s  # seconds",
                "  blocked_commands:",
                "    - \"logout\"",
                "    - \"tpa\"",
                "  nametag:",
                "    enabled: false"
        );
        Map<String, Object> user = Map.of(
                "combat.duration", "45s",
                "combat.blocked_commands", List.of("home", "spawn"),
                "combat.nametag.enabled", true
        );
        List<String> merged = merge(template, user);
        String joined = String.join("\n", merged);
        assert joined.contains("# keep me") : "comments must survive";
        assert joined.matches("(?s).*duration: 45s\\s+# seconds.*") : "scalar + inline comment";
        assert joined.contains("- home") && joined.contains("- spawn") : "list replaced";
        assert !joined.contains("logout") : "old list items removed";
        assert joined.contains("enabled: true") : "nested scalar patched";
        System.out.println("ConfigTextMerger self-check ok");
    }
}
