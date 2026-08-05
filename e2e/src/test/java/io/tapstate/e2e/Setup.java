package io.tapstate.e2e;

import java.util.List;

/**
 * The connection-provisioning facet. The three keys are three real product verbs in dependency
 * order: a connector jar must be registered before a source resource referencing it can be applied,
 * and a source model must be discovered before a target table can be derived from it.
 *
 * @param connectors connector ids whose runtime jars are registered (content-hash idempotent)
 * @param apply product resource files applied with the pipeline as one batch; sources may not be
 *     inlined, so a pipeline can only reference them by id, and ids resolve within the batch they are
 *     submitted in. The pipeline the envelope names is in that batch whether or not it is listed here
 * @param discover resource ids whose source model is discovered, feeding target-table creation
 */
public record Setup(List<String> connectors, List<String> apply, List<String> discover) {

    public static final Setup NONE = new Setup(List.of(), List.of(), List.of());

    public Setup {
        connectors = List.copyOf(connectors);
        apply = List.copyOf(apply);
        discover = List.copyOf(discover);
    }
}
