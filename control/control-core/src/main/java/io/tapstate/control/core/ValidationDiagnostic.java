package io.tapstate.control.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** A stable coded diagnostic returned by a non-mutating artifact validation. */
public record ValidationDiagnostic(String code, Map<String, Object> params) {

    public ValidationDiagnostic {
        Objects.requireNonNull(code, "code");
        params = params == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }
}
