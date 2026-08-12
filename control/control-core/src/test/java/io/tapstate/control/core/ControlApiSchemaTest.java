package io.tapstate.control.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ControlApiSchemaTest {

    private static final Set<String> MCP_OPERATIONS = Set.of(
            "connector.list", "connector.get",
            "source.list", "source.get", "source.draft",
            "connection.test", "connection.test-result", "connection.discover-schema", "connection.schema",
            "artifact.validate", "artifact.apply", "artifact.delete", "artifact.get",
            "pipeline.start", "pipeline.stop", "pipeline.status", "pipeline.metrics",
            "pipeline.snapshot", "pipeline.logs");

    @Test
    void betaMcpSurfaceIsExactlyTheOnlineAuthoringClosure() {
        Set<String> actual = ControlOperations.registry().exposedOn(Frontend.MCP, Maturity.BETA).stream()
                .map(Operation::id)
                .collect(Collectors.toSet());

        assertThat(actual).isEqualTo(MCP_OPERATIONS);
    }

    /**
     * A draft may carry the same per-resource precondition the removal takes, and the schema has to say
     * so. This is not decoration: the draft object is {@code additionalProperties: false}, so a field the
     * schema does not declare is one a schema-checking caller is told never to send — the server would
     * accept it and no caller would ever offer it. It stays optional, because a draft without one is the
     * behaviour every existing caller already has.
     */
    @Test
    void anApplyDraftMayDeclareThePreconditionItIsEditingAgainst() {
        Map<?, ?> draft = applyDraftSchema();

        Map<?, ?> properties = (Map<?, ?>) draft.get("properties");
        assertThat(properties.keySet().stream().map(String::valueOf).toList())
                .contains("expectedContentHash");
        assertThat(draft.get("additionalProperties")).isEqualTo(false);
        List<?> required = (List<?>) draft.get("required");
        assertThat(required.stream().map(String::valueOf).toList())
                .as("a draft without a precondition keeps today's behaviour")
                // The list is pinned as non-empty by naming what does stay required: on an empty one
                // the absence below holds vacuously, so a schema that lost its required fields
                // altogether would read as proof that the precondition is optional.
                .contains("content")
                .doesNotContain("expectedContentHash");
    }

    private static Map<?, ?> applyDraftSchema() {
        Map<?, ?> request = ControlApiSchema.resolve(
                ControlOperations.registry().resolve("artifact.apply").schema().params());
        Map<?, ?> drafts = (Map<?, ?>) ((Map<?, ?>) request.get("properties")).get("drafts");
        return (Map<?, ?>) drafts.get("items");
    }

    @Test
    void everyMcpOperationHasResolvableRequestAndResultSchemaRefs() {
        Map<String, Object> document = ControlApiSchema.document();
        assertThat(document).containsEntry("$schema", "https://json-schema.org/draft/2020-12/schema");
        assertThat(document.get("$defs")).isInstanceOf(Map.class);
        Map<?, ?> definitions = (Map<?, ?>) document.get("$defs");

        for (Operation operation : ControlOperations.registry().exposedOn(Frontend.MCP, Maturity.BETA)) {
            assertThat(operation.description()).as(operation.id() + " description").isNotBlank();
            assertThat(operation.schema()).as(operation.id()).isNotNull();
            assertThat(operation.schema().params()).as(operation.id() + " params").startsWith("#/$defs/");
            assertThat(operation.schema().result()).as(operation.id() + " result").startsWith("#/$defs/");
            assertThat(definitions.containsKey(
                    operation.schema().params().substring("#/$defs/".length()))).isTrue();
            assertThat(definitions.containsKey(
                    operation.schema().result().substring("#/$defs/".length()))).isTrue();
        }
    }

    /**
     * The delete tool's argument names are published the moment the tool is, and a remote model calls it
     * by those names alone. Both are required: an id with no precondition would let a caller discard a
     * version it never read, which is the one thing the conditional delete exists to prevent, and an
     * optional precondition is indistinguishable from none for a model that omits what it may omit.
     */
    @Test
    void artifactDeleteRequiresBothTheIdAndThePreconditionItWillBeCalledWith() {
        Map<?, ?> definitions = (Map<?, ?>) ControlApiSchema.document().get("$defs");
        Map<?, ?> request = (Map<?, ?>) definitions.get("ArtifactDeleteRequest");
        Map<?, ?> properties = (Map<?, ?>) request.get("properties");

        assertThat(properties.keySet().stream().map(String::valueOf).toList())
                .containsExactlyInAnyOrder("id", "expectedContentHash");
        assertThat(request.get("required")).isEqualTo(java.util.List.of("id", "expectedContentHash"));
        assertThat(request.get("additionalProperties"))
                .as("an unknown argument must be refused, not silently dropped")
                .isEqualTo(false);
    }

    @Test
    void sourceDraftDescriptionMakesTheServerOwnTheLiveConnectorContract() {
        assertThat(ControlOperations.SOURCE_DRAFT.description())
                .contains("known connector", "live connector contract", "canonical YAML");
    }

    @Test
    void sourceDraftLeavesOnlyConnectorConfigOpenForTheLiveContract() {
        Map<?, ?> definitions = (Map<?, ?>) ControlApiSchema.document().get("$defs");
        Map<?, ?> request = (Map<?, ?>) definitions.get("SourceDraftRequest");
        Map<?, ?> properties = (Map<?, ?>) request.get("properties");
        Map<?, ?> config = (Map<?, ?>) properties.get("config");

        assertThat(request.get("additionalProperties")).isEqualTo(false);
        assertThat(request.get("required")).isEqualTo(java.util.List.of("id", "connector", "config"));
        assertThat(properties.keySet().stream().map(String::valueOf).toList()).containsExactlyInAnyOrder(
                "id", "metadata", "connector", "config", "mode", "tables",
                "options", "srs", "experimental", "clearSecrets");
        assertThat(config.get("type")).isEqualTo("object");
        assertThat(config.get("additionalProperties")).isEqualTo(true);
        assertThat(definitions.get("SourceDraftResult")).isEqualTo(Map.of(
                "type", "object",
                "properties", Map.of("yaml", Map.of(
                        "type", "string", "minLength", 1,
                        "description", "Canonical tapstate/v1 Source YAML")),
                "additionalProperties", false,
                "required", java.util.List.of("yaml")));
    }
}
