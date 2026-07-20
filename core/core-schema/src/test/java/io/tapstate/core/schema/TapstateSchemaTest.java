package io.tapstate.core.schema;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TapstateSchemaTest {

    @Test
    void servesTheBundledTapstateV1Schema() {
        // The runtime loads the checked-in artifact; it must be exactly what the generator produces.
        assertThat(TapstateSchema.json()).isEqualTo(new SchemaGenerator().generate());
    }

    @Test
    void exposesTheSchemaId() {
        assertThat(TapstateSchema.id()).isEqualTo("https://tapstate.io/schema/tapstate/v1.json");
        assertThat(TapstateSchema.json())
                .contains("\"$id\": \"https://tapstate.io/schema/tapstate/v1.json\"");
    }
}
