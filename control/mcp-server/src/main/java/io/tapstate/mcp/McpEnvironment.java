package io.tapstate.mcp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Immutable process environment used only for connector configuration expansion. */
record McpEnvironment(Map<String, String> values) {

    private static final Set<String> CONTROL_VARIABLES = Set.of(
            "TAPSTATE_TOKEN",
            "TAPSTATE_SERVER_URL");

    McpEnvironment {
        Map<String, String> expansionValues = new LinkedHashMap<>();
        values.forEach((name, value) -> {
            if (!CONTROL_VARIABLES.contains(name)) {
                expansionValues.put(name, value);
            }
        });
        values = Map.copyOf(expansionValues);
    }
}
