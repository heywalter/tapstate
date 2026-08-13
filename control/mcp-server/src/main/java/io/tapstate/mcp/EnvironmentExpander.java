package io.tapstate.mcp;

import io.tapstate.core.common.TapstateException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recursively expands Source config environment references immediately before the HTTP write. */
final class EnvironmentExpander {

    private static final Pattern REFERENCE = Pattern.compile(
            "\\$\\{(?:([A-Za-z_][A-Za-z0-9_]*)|var:([A-Za-z_][A-Za-z0-9_]*):([^}]*))}");

    private EnvironmentExpander() {
    }

    static Object expand(Object value, Map<String, String> environment) {
        return switch (value) {
            case Map<?, ?> map -> expandMap(map, environment);
            case List<?> list -> expandList(list, environment);
            case String text -> expandText(text, environment);
            case null -> null;
            default -> value;
        };
    }

    static boolean containsReference(Object value) {
        return switch (value) {
            case Map<?, ?> map -> map.values().stream().anyMatch(EnvironmentExpander::containsReference);
            case List<?> list -> list.stream().anyMatch(EnvironmentExpander::containsReference);
            case String text -> REFERENCE.matcher(text).find();
            case null, default -> false;
        };
    }

    static Object restoreReferences(Object value, Object original) {
        if (value instanceof Map<?, ?> output && original instanceof Map<?, ?> input) {
            Map<String, Object> restored = new LinkedHashMap<>();
            output.forEach((key, item) -> restored.put(
                    String.valueOf(key), restoreReferences(item, inputValue(input, key))));
            return restored;
        }
        if (value instanceof List<?> output && original instanceof List<?> input) {
            List<Object> restored = new ArrayList<>(output.size());
            for (int index = 0; index < output.size(); index++) {
                Object originalItem = index < input.size() ? input.get(index) : null;
                restored.add(restoreReferences(output.get(index), originalItem));
            }
            return restored;
        }
        if (value instanceof String && original instanceof String text && REFERENCE.matcher(text).find()) {
            return text;
        }
        return value;
    }

    private static Map<String, Object> expandMap(Map<?, ?> source, Map<String, String> environment) {
        Map<String, Object> expanded = new LinkedHashMap<>();
        source.forEach((key, value) -> expanded.put(String.valueOf(key), expand(value, environment)));
        return expanded;
    }

    private static List<Object> expandList(List<?> source, Map<String, String> environment) {
        List<Object> expanded = new ArrayList<>(source.size());
        source.forEach(value -> expanded.add(expand(value, environment)));
        return expanded;
    }

    private static Object inputValue(Map<?, ?> input, Object key) {
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            if (String.valueOf(entry.getKey()).equals(String.valueOf(key))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String expandText(String text, Map<String, String> environment) {
        Matcher matcher = REFERENCE.matcher(text);
        StringBuilder expanded = new StringBuilder(text.length());
        while (matcher.find()) {
            String variable = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
            String replacement = environment.get(variable);
            if (replacement == null) {
                replacement = matcher.group(3);
            }
            if (replacement == null) {
                throw new TapstateException(
                        McpError.ENVIRONMENT_MISSING, Map.of("variable", variable), null);
            }
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(expanded);
        return expanded.toString();
    }
}
