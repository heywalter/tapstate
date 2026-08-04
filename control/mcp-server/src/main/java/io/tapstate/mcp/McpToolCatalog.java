package io.tapstate.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.tapstate.control.core.ControlApiSchema;
import io.tapstate.control.core.ControlOperations;
import io.tapstate.control.core.Frontend;
import io.tapstate.control.core.Maturity;
import io.tapstate.control.core.Operation;
import io.tapstate.control.core.Scope;
import io.tapstate.core.common.JsonWriter;

import java.util.List;

/** Programmatic Spring AI tool projection derived exclusively from the operation registry. */
final class McpToolCatalog {

    private McpToolCatalog() {
    }

    static List<Operation> operations(boolean allowWrite) {
        return ControlOperations.registry().exposedOn(Frontend.MCP, Maturity.BETA).stream()
                .filter(operation -> operation.scope() == Scope.READ
                        || allowWrite && operation.scope() == Scope.WRITE)
                .toList();
    }

    static String toolName(Operation operation) {
        return operation.id().replace('.', '_').replace('-', '_');
    }

    static List<SyncToolSpecification> specifications(
            boolean allowWrite, McpOperationExecutor executor) {
        return operations(allowWrite).stream()
                .map(operation -> specification(operation, executor))
                .toList();
    }

    private static SyncToolSpecification specification(
            Operation operation, McpOperationExecutor executor) {
        McpSchema.Tool tool = McpSchema.Tool.builder(toolName(operation))
                .description(operation.description())
                .inputSchema(ControlApiSchema.resolve(operation.schema().params()))
                .outputSchema(ControlApiSchema.resolve(operation.schema().result()))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            McpResult result = executor.execute(operation, request.arguments());
            return McpSchema.CallToolResult.builder()
                    .addTextContent(JsonWriter.write(result.body()))
                    .structuredContent(result.body())
                    .isError(result.error())
                    .build();
        });
    }
}
