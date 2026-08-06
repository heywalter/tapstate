package io.tapstate.control.restapi;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.tapstate.control.core.PipelineMetrics;

import java.util.Map;

/**
 * The metrics read face on the wire: the pipeline id, the open map of numeric run statistics, and the
 * per-table sink-acked source positions ({@code table -> opaque srcpos}).
 *
 * <p>The positions ride beside the metrics map rather than inside it. A position is a string and every
 * metrics cell is a number, so nesting them put one string-valued cell in an otherwise numeric map and made
 * every reader type-test a cell before using it — the control read model already keeps the two apart, and
 * this is the wire saying the same thing. The positions are absent until one is acked, mirroring the
 * never-faked, empty-is-unavailable rule the numeric metrics follow.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record PipelineMetricsResponse(String pipelineId, Map<String, Long> metrics, Map<String, String> perTableOffset) {

    static PipelineMetricsResponse of(PipelineMetrics metrics) {
        return new PipelineMetricsResponse(metrics.pipelineId(), metrics.metrics(),
                metrics.positions().isEmpty() ? null : metrics.positions());
    }
}
