package io.tapstate.core.dsl;

import io.tapstate.core.common.TapstateErrorCode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One finding an offline rule has about a batch it is not refusing: a coded diagnostic with the named
 * arguments its catalog text is rendered from.
 *
 * <p>It is returned rather than thrown. Refusing is the validation stack's job, and a rule that threw
 * here would turn advice into a gate — which is the one thing this kind of finding exists not to be.
 * Returning it also lets one pass report everything it found, where throwing would stop at the first.
 */
public record Advisory(TapstateErrorCode code, Map<String, Object> params) {

    public Advisory {
        Objects.requireNonNull(code, "code");
        params = params == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }
}
