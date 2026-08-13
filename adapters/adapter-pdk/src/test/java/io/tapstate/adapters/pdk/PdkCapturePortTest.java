package io.tapstate.adapters.pdk;

import io.tapstate.core.common.TapstateException;
import io.tapstate.core.event.Envelope;
import io.tapstate.core.event.Op;
import io.tapstate.spi.capture.CaptureBatch;
import io.tapstate.spi.capture.CaptureConfig;
import io.tapstate.spi.capture.CaptureListener;
import io.tapstate.spi.capture.ConnectionReport;
import io.tapstate.spi.capture.DiscoveredSchema;
import io.tapstate.spi.capture.Subscription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The read-side PDK bridge: {@link PdkCapturePort} driving a connector's registered read functions
 * through the frozen PDK contract, with every diagnosable failure surfaced as a coded connector-domain
 * exception. The tests use synthetic connectors compiled at test time (a real connector needs the PDK
 * runtime the host does not yet provide), so the assembly and the coded-error paths are proven
 * deterministically without any real connector jar.
 */
class PdkCapturePortTest {

    /** A provisioner that hands back one fixed connector ref, whatever id is asked for. */
    private static ConnectorProvisioner provisioner(Path jar, String className, String requiredLevel) {
        ConnectorRef ref = new ConnectorRef(List.of(jar), className, "2.0.8", requiredLevel);
        return connectorId -> ref;
    }

    private static CaptureConfig config(String... streams) {
        return new CaptureConfig("demo", Map.of(), List.of(streams));
    }

    // ---- structural failures (resolved before any connector data flows) --------------------------

    @Test
    void refusesAnIncompatibleConnectorWithACodeThatNamesTheLevels(@TempDir Path dir) throws Exception {
        Path jar = Synthetic.emittingSource(dir);
        PdkCapturePort port = new PdkCapturePort(provisioner(jar, "synthetic.EmittingSource", "99"));
        assertThatThrownBy(() -> port.snapshot(config("t1")))
                .isInstanceOf(TapstateException.class)
                .satisfies(e -> {
                    TapstateException ce = (TapstateException) e;
                    assertThat(ce.code()).isEqualTo(ConnectorError.API_LEVEL_INCOMPATIBLE);
                    assertThat(ce.args()).containsEntry("connector", "demo")
                            .containsEntry("required", 99).containsEntry("provided", 1);
                });
    }

    @Test
    void aMissingEntryClassIsACodedClassNotFound(@TempDir Path dir) throws Exception {
        Path jar = Synthetic.emittingSource(dir);
        PdkCapturePort port = new PdkCapturePort(provisioner(jar, "synthetic.Missing", null));
        assertThatThrownBy(() -> port.snapshot(config("t1")))
                .isInstanceOf(TapstateException.class)
                .satisfies(e -> {
                    TapstateException ce = (TapstateException) e;
                    assertThat(ce.code()).isEqualTo(ConnectorError.CLASS_NOT_FOUND);
                    assertThat(ce.args()).containsEntry("connector", "demo")
                            .containsEntry("class", "synthetic.Missing");
                });
    }

    @Test
    void aConnectorThatCannotBeInstantiatedIsACodedLoadFailure(@TempDir Path dir) throws Exception {
        Path jar = Synthetic.ctorThrowsSource(dir);
        PdkCapturePort port = new PdkCapturePort(provisioner(jar, "synthetic.CtorThrows", null));
        assertThatThrownBy(() -> port.snapshot(config("t1")))
                .isInstanceOf(TapstateException.class)
                .satisfies(e -> assertThat(((TapstateException) e).code()).isEqualTo(ConnectorError.LOAD_FAILED));
    }

    @Test
    void aConnectorWhoseStaticInitializerThrowsIsACodedLoadFailure(@TempDir Path dir) throws Exception {
        // Construction fails with a link/init Error (ExceptionInInitializerError), not a RuntimeException;
        // it must still surface as a coded load failure, not crash bare.
        Path jar = Synthetic.staticThrowsSource(dir);
        PdkCapturePort port = new PdkCapturePort(provisioner(jar, "synthetic.StaticThrows", null));
        assertThatThrownBy(() -> port.snapshot(config("t1")))
                .isInstanceOf(TapstateException.class)
                .satisfies(e -> assertThat(((TapstateException) e).code()).isEqualTo(ConnectorError.LOAD_FAILED));
    }

    @Test
    void snapshotOnAConnectorWithoutBatchReadCrashesBareNotCoded(@TempDir Path dir) throws Exception {
        // Asking a non-source connector to snapshot is a caller invariant violation (validated upstream),
        // so it must crash bare and NOT be laundered into a coded capture failure.
        Path jar = Synthetic.countingSink(dir);
        PdkCapturePort port = new PdkCapturePort(provisioner(jar, "synthetic.CountingSink", null));
        assertThatThrownBy(() -> port.snapshot(config("t1")))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(TapstateException.class);
    }

    // ---- snapshot drive: batchRead -> decodeSnapshotRow ------------------------------------------

    @Test
    void snapshotDrivesBatchReadAndYieldsSnapshotReadEnvelopes(@TempDir Path dir) throws Exception {
        Path jar = Synthetic.emittingSource(dir);
        PdkCapturePort port = new PdkCapturePort(provisioner(jar, "synthetic.EmittingSource", null));
        List<Envelope> got = new ArrayList<>();
        try (CaptureBatch batch = port.snapshot(config("t1"))) {
            while (batch.hasNext()) {
                got.add(batch.next());
            }
        }
        assertThat(got).hasSize(2);
        assertThat(got).allSatisfy(e -> {
            assertThat(e.op()).isEqualTo(Op.READ);
            assertThat(e.src()).isEqualTo("t1");
        });
        // The synthetic source emits an int; the decode boundary carries it into the value model, so
        // what the port yields is the one integer width every reader downstream binds to.
        assertThat(got.get(0).after()).containsEntry("id", 1L);
        assertThat(got.get(1).after()).containsEntry("id", 2L);
    }

    @Test
    void snapshotHandsTheConnectorItsDiscoveredTableNotABareName(@TempDir Path dir) throws Exception {
        // A real connector builds its read from the table's own columns - a mysql SELECT names them - and
        // handed a bare name with no columns it reads nothing or fails outright. This connector emits one
        // row per column of the table it is given, and its discovery reports a single column; so a snapshot
        // that read through a bare name would yield zero, and one through the discovered table yields one.
        Path jar = Synthetic.tableAwareSource(dir);
        PdkCapturePort port = new PdkCapturePort(provisioner(jar, "synthetic.TableAware", null));
        List<Envelope> got = new ArrayList<>();
        try (CaptureBatch batch = port.snapshot(config("t1"))) {
            while (batch.hasNext()) {
                got.add(batch.next());
            }
        }
        assertThat(got).hasSize(1);
    }

    @Test
    void snapshotWithoutExplicitStreamsInitsTheConnectorExactlyOnce(@TempDir Path dir) throws Exception {
        // Empty streams means "every stream": the drive discovers the stream names and reads them. It
        // must init the connector once, not once for discovery and again for the read — the connector
        // here refuses a second init on the same instance.
        Path jar = Synthetic.singleInitSource(dir);
        PdkCapturePort port = new PdkCapturePort(provisioner(jar, "synthetic.SingleInit", null));
        List<Envelope> got = new ArrayList<>();
        try (CaptureBatch batch = port.snapshot(config())) {
            while (batch.hasNext()) {
                got.add(batch.next());
            }
        }
        assertThat(got).hasSize(1);
        assertThat(got.get(0).op()).isEqualTo(Op.READ);
    }

    @Test
    void aConnectorThatThrowsWhileReadingIsACodedCaptureFailure(@TempDir Path dir) throws Exception {
        Path jar = Synthetic.throwingReadSource(dir);
        PdkCapturePort port = new PdkCapturePort(provisioner(jar, "synthetic.ThrowingRead", null));
        assertThatThrownBy(() -> port.snapshot(config("t1")))
                .isInstanceOf(TapstateException.class)
                .satisfies(e -> assertThat(((TapstateException) e).code()).isEqualTo(ConnectorError.CAPTURE_FAILED));
    }

    @Test
    void anUnprojectableRowIsACodedProjectionFailure(@TempDir Path dir) throws Exception {
        // The connector emits a delete-shaped event from batchRead; snapshot rows must be insert-shaped,
        // so the codec refuses it. That is a projection failure, distinct from a connector read failure.
        Path jar = Synthetic.badRowSource(dir);
        PdkCapturePort port = new PdkCapturePort(provisioner(jar, "synthetic.BadRow", null));
        assertThatThrownBy(() -> port.snapshot(config("t1")))
                .isInstanceOf(TapstateException.class)
                .satisfies(e -> assertThat(((TapstateException) e).code()).isEqualTo(ConnectorError.PROJECTION_FAILED));
    }

    // ---- cdc drive: streamRead -> decodeChange ---------------------------------------------------

    @Test
    void cdcDrivesStreamReadAndDeliversDecodedChangeEnvelopes(@TempDir Path dir) throws Exception {
        Path jar = Synthetic.emittingSource(dir);
        PdkCapturePort port = new PdkCapturePort(provisioner(jar, "synthetic.EmittingSource", null));
        List<Envelope> got = new CopyOnWriteArrayList<>();
        CountDownLatch three = new CountDownLatch(3);
        try (Subscription sub = port.cdc(config("t1"), e -> {
            got.add(e);
            three.countDown();
        })) {
            assertThat(three.await(5, TimeUnit.SECONDS)).as("three change events delivered").isTrue();
        }
        assertThat(got).extracting(Envelope::op).containsExactly(Op.INSERT, Op.UPDATE, Op.DELETE);
    }

    @Test
    void cdcThatFailsWhileStreamingReportsItThroughOnError(@TempDir Path dir) throws Exception {
        // The cdc stream runs on a daemon thread, so a stream that dies cannot throw back to whoever
        // started it; the failure reaches the caller only through the listener's error channel. Without
        // that channel a dead tail is invisible above this port.
        Path jar = Synthetic.throwingStreamSource(dir);
        PdkCapturePort port = new PdkCapturePort(provisioner(jar, "synthetic.ThrowingStream", null));
        AtomicReference<Throwable> reported = new AtomicReference<>();
        CountDownLatch failed = new CountDownLatch(1);
        CaptureListener listener = new CaptureListener() {
            @Override
            public void onEvent(Envelope event) {
            }

            @Override
            public void onError(Throwable error) {
                reported.set(error);
                failed.countDown();
            }
        };
        try (Subscription sub = port.cdc(config("t1"), listener)) {
            assertThat(failed.await(5, TimeUnit.SECONDS))
                    .as("the cdc stream failure was reported through onError")
                    .isTrue();
        }
        assertThat(reported.get()).isNotNull();
    }

    /**
     * What the error channel carries is a coded capture failure, not the connector's own throwable.
     *
     * <p>Reporting it is half the contract; the other half is what the reader can do with it. This
     * failure is the last one in the pipeline to keep its diagnosis: nothing above this port knows what a
     * Debezium exception is, and the reading a user is shown - the published failure code - is built by
     * walking the cause chain for something coded. An uncoded throwable makes that walk find nothing, so a
     * connector refusing to start for a reason it stated precisely ("the server is not configured to use a
     * FULL binlog_row_image") reaches the user as "the job died", with the sentence that says what to
     * change surviving only in a log line.
     *
     * <p>The snapshot side of this same port already wraps what it catches. The stream side was the one
     * that did not, which is why the codes that went missing were the ones a cdc failure would have
     * carried.
     */
    @Test
    void cdcThatFailsWhileStreamingReportsACodedCaptureFailure(@TempDir Path dir) throws Exception {
        Path jar = Synthetic.throwingStreamSource(dir);
        PdkCapturePort port = new PdkCapturePort(provisioner(jar, "synthetic.ThrowingStream", null));
        AtomicReference<Throwable> reported = new AtomicReference<>();
        CountDownLatch failed = new CountDownLatch(1);
        CaptureListener listener = new CaptureListener() {
            @Override
            public void onEvent(Envelope event) {
            }

            @Override
            public void onError(Throwable error) {
                reported.set(error);
                failed.countDown();
            }
        };
        try (Subscription sub = port.cdc(config("t1"), listener)) {
            assertThat(failed.await(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(reported.get())
                .as("what the error channel carries, which is what the published code is derived from")
                .isInstanceOf(TapstateException.class);
        TapstateException coded = (TapstateException) reported.get();
        assertThat(coded.code().code()).isEqualTo(ConnectorError.CAPTURE_FAILED.code());
        assertThat(coded.args())
                .as("the connector that failed, and enough of its own words to act on")
                .containsKey("connector")
                .containsKey("detail");
        assertThat(coded.getCause())
                .as("the connector's own throwable is kept underneath rather than replaced by the code")
                .isNotNull();
    }

    @Test
    void cdcSubscriptionCloseIsIdempotent(@TempDir Path dir) throws Exception {
        // The Subscription contract promises idempotent close; a second close must be a no-op, not a
        // second interrupt / stop / loader close.
        Path jar = Synthetic.emittingSource(dir);
        PdkCapturePort port = new PdkCapturePort(provisioner(jar, "synthetic.EmittingSource", null));
        Subscription sub = port.cdc(config("t1"), e -> {
        });
        sub.close();
        assertThatCode(sub::close).doesNotThrowAnyException();
    }

    // ---- testConnection / discoverSchema drive ---------------------------------------------------

    @Test
    void discoverSchemaDrivesTheConnectorAndMapsTablesAndFields(@TempDir Path dir) throws Exception {
        Path jar = Synthetic.emittingSource(dir);
        PdkCapturePort port = new PdkCapturePort(provisioner(jar, "synthetic.EmittingSource", null));
        DiscoveredSchema schema = port.discoverSchema(config());
        assertThat(schema.tables()).hasSize(1);
        assertThat(schema.tables().get(0).name()).isEqualTo("t1");
        assertThat(schema.tables().get(0).fields()).extracting("name").contains("id");
    }

    @Test
    void testConnectionReportsTheDiscoveredSchemaAndASample(@TempDir Path dir) throws Exception {
        Path jar = Synthetic.emittingSource(dir);
        PdkCapturePort port = new PdkCapturePort(provisioner(jar, "synthetic.EmittingSource", null));
        ConnectionReport report = port.testConnection(config("t1"));
        assertThat(report.schema().tables()).extracting("name").contains("t1");
        assertThat(report.sample()).isNotEmpty();
    }
}
