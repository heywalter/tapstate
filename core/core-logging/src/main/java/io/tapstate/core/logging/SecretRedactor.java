package io.tapstate.core.logging;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Redacts runtime secret values before log text reaches an observable sink. Registrations are
 * grouped by owner so replacing or deleting a Source cannot leave stale values behind.
 */
public final class SecretRedactor {

    private static final String REDACTED = "********";

    private final Map<String, List<String>> valuesByOwner = new LinkedHashMap<>();
    private final AtomicReference<List<String>> snapshot = new AtomicReference<>(List.of());

    /** Replaces every secret associated with {@code owner}. Blank values are ignored. */
    public synchronized void replace(String owner, Collection<String> values) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(values, "values");
        List<String> normalized = values.stream()
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            valuesByOwner.remove(owner);
        } else {
            valuesByOwner.put(owner, normalized);
        }
        rebuildSnapshot();
    }

    /** Removes every secret associated with {@code owner}. */
    public synchronized void remove(String owner) {
        Objects.requireNonNull(owner, "owner");
        valuesByOwner.remove(owner);
        rebuildSnapshot();
    }

    /** Replaces every currently registered value in {@code text} with a fixed marker. */
    public String redact(String text) {
        if (text == null) {
            return null;
        }
        String result = text;
        for (String secret : snapshot.get()) {
            result = result.replace(secret, REDACTED);
        }
        return result;
    }

    private void rebuildSnapshot() {
        snapshot.set(valuesByOwner.values().stream()
                .flatMap(Collection::stream)
                .distinct()
                .sorted((left, right) -> Integer.compare(right.length(), left.length()))
                .toList());
    }
}
