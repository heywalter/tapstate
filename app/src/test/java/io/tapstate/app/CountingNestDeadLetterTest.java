package io.tapstate.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.tapstate.core.event.ChainPosition;
import io.tapstate.core.event.SourceOrder;
import io.tapstate.runtime.engine.nest.ElementRef;
import io.tapstate.runtime.engine.nest.NestElement;
import io.tapstate.runtime.engine.nest.NestInbound;
import io.tapstate.runtime.engine.nest.NestVertex;
import io.tapstate.runtime.engine.nest.ReleasedChild;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Where a change that can never reach a document goes while the product has not decided where it should
 * go: counted and warned about, never swallowed. A no-op would be indistinguishable from a working nest
 * from the outside, because the rows it drops were never going to appear in a document anyway.
 */
class CountingNestDeadLetterTest {

    private static final NestVertex ITEMS = new NestVertex(List.of("items"), "items", "nest.p.items",
            List.of("order_id"), List.of("customer_id"),
            List.of(new NestInbound(0, "item", List.of("items"), List.of("order_id"), List.of("id"))));

    private static ReleasedChild released(int id) {
        return new ReleasedChild(
                new NestElement(
                        new ElementRef(List.of("items"), 1, List.of(id), id),
                        Map.of("id", id),
                        new SourceOrder(1L, id),
                        Map.of("item", new ChainPosition(new SourceOrder(1L, id), "t" + id))),
                Duration.ofMinutes(id));
    }

    @Test
    void countsEveryChangeHandedToIt() {
        CountingNestDeadLetter deadLetter = new CountingNestDeadLetter();

        for (int i = 0; i < 25; i++) {
            deadLetter.unassemblable(ITEMS, released(i));
        }

        // Every one, not only the ones it logged: the log is throttled so a parent deleted with many
        // children below it does not bury the rest of it, and the count is what still says how many.
        assertThat(deadLetter.count()).isEqualTo(25);
    }

    @Test
    void survivesSerializationBecauseItTravelsToTheMemberRunningTheVertex() throws Exception {
        CountingNestDeadLetter deadLetter = new CountingNestDeadLetter();
        deadLetter.unassemblable(ITEMS, released(1));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(deadLetter);
        }
        Object read;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            read = in.readObject();
        }

        // The binding is carried onto the DAG, so a non-serializable field here would not fail a build or a
        // unit test -- it would fail the job, at submit time, on a pipeline that had passed everything else.
        assertThat(read).isInstanceOf(CountingNestDeadLetter.class);
        assertThat(((CountingNestDeadLetter) read).count()).isEqualTo(1);
    }
}
