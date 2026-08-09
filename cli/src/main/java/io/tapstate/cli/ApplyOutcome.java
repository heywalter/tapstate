package io.tapstate.cli;

import java.util.List;
import java.util.Map;

/**
 * The outcome of a remote {@code POST /api/artifacts:apply}. Either the batch applied and the server
 * reported one item per artifact (created / updated / unchanged), or it was refused with a coded reason (a
 * validation failure is a {@code dsl.*} code), or the server could not be reached. Sealed so the caller
 * renders each branch without try/catch, mirroring the never-throw transport seam.
 */
sealed interface ApplyOutcome {

    /**
     * The batch applied; one item per submitted artifact, in submission order, plus whatever the server
     * had to say about a batch it nonetheless applied. A warning is a note, never a refusal — everything
     * in {@code items} was written either way.
     */
    record Applied(List<Item> items, List<Warning> warnings) implements ApplyOutcome {
        public Applied {
            items = List.copyOf(items);
            warnings = List.copyOf(warnings);
        }

        /** An apply the server had nothing further to say about. */
        Applied(List<Item> items) {
            this(items, List.of());
        }
    }

    /** The server refused the apply with a coded reason already rendered to a message. */
    record Rejected(String code, String message) implements ApplyOutcome {
    }

    /** The server could not be reached (connection refused, timeout, or a malformed target). */
    record Unreachable() implements ApplyOutcome {
    }

    /**
     * One artifact's apply result: its id, kind, and how it changed — {@code CREATED} (written),
     * {@code UPDATED} (overwritten) or {@code UNCHANGED} (an identical no-op the server did not write).
     */
    record Item(String id, String kind, String change) {
    }

    /**
     * One advisory finding about an applied batch, as the server sent it: its canonical code and the
     * named params that fill the message in. It travels uncoded-for-display on purpose — the CLI renders
     * it from its own bundled catalog, the same way it renders a locally raised code, and a code this
     * catalog does not know renders as itself rather than disappearing.
     */
    record Warning(String code, Map<String, Object> params) {
        public Warning {
            params = params == null ? Map.of() : Map.copyOf(params);
        }
    }
}
