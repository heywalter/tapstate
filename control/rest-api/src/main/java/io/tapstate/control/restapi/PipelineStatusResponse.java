package io.tapstate.control.restapi;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.tapstate.control.core.PipelineStatus;
import io.tapstate.core.lifecycle.ObservationFailure;
import io.tapstate.core.lifecycle.PipelineState;
import io.tapstate.messages.MessageCatalog;

import java.util.Map;
import java.util.TreeMap;

/**
 * The wire shape of a pipeline's status: its lifecycle state, plus why its run died when it did. A failed
 * state that cannot say what failed is only half an answer, and sending the reader to the logs for the rest
 * is what the coded reason exists to avoid.
 *
 * <p>The failure is omitted while the pipeline is healthy rather than serialized as null, so a client tells
 * "nothing wrong" from "something wrong" by presence alone.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record PipelineStatusResponse(String pipelineId, PipelineState state, Failure failure) {

    /**
     * A coded failure as a client reads it: the canonical code string (the stable identity — the enum never
     * leaves the process), its named arguments sorted for a stable machine contract, and the message
     * rendered from both through the shared catalog, so every face prints one wording.
     */
    record Failure(String code, Map<String, Object> params, String message) {
    }

    static PipelineStatusResponse of(PipelineStatus status, MessageCatalog catalog) {
        return new PipelineStatusResponse(status.pipelineId(), status.state(), failure(status.failure(), catalog));
    }

    private static Failure failure(ObservationFailure failure, MessageCatalog catalog) {
        if (failure == null) {
            return null;
        }
        Map<String, Object> params = new TreeMap<>(failure.params());
        return new Failure(failure.code(), params, catalog.render(failure.code(), params).message());
    }
}
