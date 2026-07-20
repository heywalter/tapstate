/**
 * Source capture port: the read-side extraction contract. A pure interface over the standard event
 * envelope — bounded snapshot reads, an unbounded CDC stream, a connection test and schema
 * discovery. Which phases run is driven by the pipeline read mode as a {@link
 * io.tapstate.spi.capture.CapturePlan}; the {@link io.tapstate.spi.capture.CapturePhase} of an event
 * follows its op; the snapshot → cdc hand-off is marked by an opaque {@link
 * io.tapstate.spi.capture.SourcePosition}. Rule R2: this module depends only on the core ring.
 */
package io.tapstate.spi.capture;
