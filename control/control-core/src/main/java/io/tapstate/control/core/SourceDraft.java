package io.tapstate.control.core;

import io.tapstate.core.model.Metadata;

import java.util.ArrayList;
import java.util.Collections;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Structured input for rendering a Source artifact without persisting it. */
public record SourceDraft(
        String id,
        Metadata metadata,
        String connector,
        Map<String, Object> config,
        String mode,
        List<SourceTableDraft> tables,
        Map<String, Object> options,
        SourceSrs srs,
        Map<String, Object> experimental,
        List<String> clearSecrets) {

    public SourceDraft {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(connector, "connector");
        config = copyJsonMap(config, false);
        tables = tables == null ? null : Collections.unmodifiableList(new ArrayList<>(tables));
        options = copyJsonMap(options, true);
        experimental = copyJsonMap(experimental, true);
        clearSecrets = clearSecrets == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(clearSecrets));
    }

    @Override
    public String toString() {
        return "SourceDraft[id=" + id
                + ", connector=" + connector
                + ", configKeys=" + config.keySet()
                + ", clearSecrets=" + clearSecrets
                + "]";
    }

    static Map<String, Object> copyJsonMap(Map<String, Object> value, boolean preserveNull) {
        if (value == null) {
            return preserveNull ? null : Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            if (entry.getKey() == null) {
                throw new IllegalArgumentException("JSON object keys must not be null");
            }
            result.put(entry.getKey(), copyJsonValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    static Object copyJsonValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number number) {
            if (!(number instanceof Byte
                    || number instanceof Short
                    || number instanceof Integer
                    || number instanceof Long
                    || number instanceof BigInteger
                    || number instanceof BigDecimal
                    || number instanceof Float
                    || number instanceof Double)) {
                throw new IllegalArgumentException(
                        "unsupported JSON value type: " + value.getClass().getName());
            }
            if (number instanceof Double doubleValue && !Double.isFinite(doubleValue)
                    || number instanceof Float floatValue && !Float.isFinite(floatValue)) {
                throw new IllegalArgumentException("JSON numbers must be finite");
            }
            return value;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(item -> result.add(copyJsonValue(item)));
            return Collections.unmodifiableList(result);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("JSON object keys must be strings");
                }
                result.put(key, copyJsonValue(entry.getValue()));
            }
            return Collections.unmodifiableMap(result);
        }
        throw new IllegalArgumentException("unsupported JSON value type: " + value.getClass().getName());
    }

    /** Structured shared-record-store settings accepted by the Source view. */
    public record SourceSrs(
            String key,
            String retention,
            String schemaEvolution,
            Boolean queryable,
            Boolean enabled) {
    }
}
