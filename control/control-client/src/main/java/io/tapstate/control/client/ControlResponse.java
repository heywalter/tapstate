package io.tapstate.control.client;

import java.util.Map;

/** Structured result of one HTTP control-plane exchange. */
public sealed interface ControlResponse {

    record Success(int status, Object body) implements ControlResponse {
    }

    record Rejected(int status, String code, String message, Map<String, Object> params)
            implements ControlResponse {
        public Rejected {
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }

    record Unreachable() implements ControlResponse {
    }
}
