package io.tapstate.adapters.pdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.tapstate.spi.sink.TargetField;
import io.tapstate.spi.sink.TargetTable;
import io.tapdata.entity.schema.TapTable;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Builds a PDK {@link TapTable} descriptor from a resolved tapstate {@link TargetTable}: the target's
 * fields become typed columns and the primary-key fields become the key an upsert matches on, in field
 * order. This is the write-side model a connector reads to create the table and to coerce each row
 * value; the projection is exercised in isolation here, apart from any live connector.
 */
class TargetTapTableTest {

    @Test
    void carriesTheTargetNameAndEachFieldWithItsType() {
        TapTable table = TargetTapTable.build(new TargetTable("orders",
                List.of(new TargetField("id", "bigint", true), new TargetField("name", "varchar", false))));

        assertThat(table.getName()).isEqualTo("orders");
        assertThat(table.getNameFieldMap()).containsOnlyKeys("id", "name");
        assertThat(table.getNameFieldMap().get("id").getDataType()).isEqualTo("bigint");
        assertThat(table.getNameFieldMap().get("name").getDataType()).isEqualTo("varchar");
    }

    @Test
    void derivesThePrimaryKeyFromTheFlaggedFieldsInFieldOrder() {
        TapTable table = TargetTapTable.build(new TargetTable("orders", List.of(
                new TargetField("region", "varchar", true),
                new TargetField("payload", "text", false),
                new TargetField("id", "bigint", true))));

        // A composite key in field order: region before id, and the non-key column excluded.
        assertThat(table.primaryKeys()).containsExactly("region", "id");
    }

    @Test
    void hasNoPrimaryKeyWhenNoFieldIsFlagged() {
        TapTable table = TargetTapTable.build(new TargetTable("events",
                List.of(new TargetField("payload", "text", false))));

        assertThat(table.primaryKeys()).isEmpty();
    }

    @Test
    void anUnresolvedFieldTypePassesThroughAsNullForTheConnectorToInfer() {
        TapTable table = TargetTapTable.build(new TargetTable("orders",
                List.of(new TargetField("id", null, true))));

        assertThat(table.getNameFieldMap().get("id").getDataType()).isNull();
    }

    /**
     * A descriptor carrying no columns still answers what a connector asks of it.
     *
     * <p>Declaring no columns is an ordinary outcome - a stream whose table was never discovered reaches
     * the sink with its name and nothing else, and the contract for that case is that the connector decides
     * the structure. What is not ordinary is the descriptor being unable to answer at all, and that is what
     * it did: the column map is created by the first column added, so a descriptor that never got one
     * carried a null map and every read of its key threw. The throw surfaced inside the connector, several
     * frames below anything that knew the model was absent, and was reported as the connector failing to
     * write rather than as the model never having been resolved.
     */
    @Test
    void aDescriptorForAModelWithNoColumnsAnswersAnEmptyKeyRatherThanThrowing() {
        TapTable table = TargetTapTable.build(new TargetTable("orders", List.of()));

        assertThatCode(table::primaryKeys)
                .as("reading the key of a descriptor built from a model carrying no columns")
                .doesNotThrowAnyException();
        assertThat(table.primaryKeys()).isEmpty();
    }

    @Test
    void aDescriptorForAStreamWithNoModelAnswersAnEmptyKeyRatherThanThrowing() {
        TapTable table = TargetTapTable.bare("order_doc");

        assertThat(table.getName()).isEqualTo("order_doc");
        assertThatCode(table::primaryKeys)
                .as("reading the key of a descriptor for a stream no model was resolved for")
                .doesNotThrowAnyException();
        assertThat(table.primaryKeys()).isEmpty();
    }

    @Test
    void aBareDescriptorCarriesNoColumns() {
        assertThat(TargetTapTable.bare("order_doc").getNameFieldMap()).isEmpty();
    }
}
