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
