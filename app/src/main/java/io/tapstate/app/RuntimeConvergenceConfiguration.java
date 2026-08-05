package io.tapstate.app;

import io.tapstate.runtime.engine.Engine;
import io.tapstate.runtime.scheduler.LifecycleActuator;
import io.tapstate.runtime.scheduler.ObservationPublisher;
import io.tapstate.runtime.scheduler.PipelineConverger;
import io.tapstate.spi.store.StorePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * Wires the runtime convergence loop into the assembly root — the first runtime-ring module the server
 * runs. The framework-free {@link PipelineConverger} (runtime ring, no Spring) is constructed here over
 * the driver-free store port, and a scheduled {@link ConvergenceDriver} (this assembly layer, where the
 * scheduler lives — the framework is banned in the runtime ring) ticks it so actual state tracks desired
 * intent. Control writes desired intent; this side writes actual state; the two meet only at the store.
 *
 * <p>Gated, like the store it reads and writes, on {@code tapstate.store.mongo.enabled}: a run with no store
 * brings up neither the store nor the convergence loop.
 */
@Configuration
@ConditionalOnProperty(prefix = "tapstate.store.mongo", name = "enabled", matchIfMissing = true)
@EnableScheduling
class RuntimeConvergenceConfiguration {

    @Bean
    PipelineConverger pipelineConverger(StorePort storePort, LifecycleActuator lifecycleActuator, Clock clock) {
        return new PipelineConverger(storePort.desired(), storePort.state(), lifecycleActuator, clock);
    }

    @Bean
    ObservationPublisher observationPublisher(StorePort storePort, Engine engine) {
        // The publisher's three run-statistic sources: recordCount and the per-chain frontier readings ride
        // from the engine's live Jet job, and the per-table sink-acked positions from the store. All are
        // ports, so the scheduler stays clear of the engine and the store that back them; a stopped pipeline,
        // an unacked table or a chain with no reading reports absence, not zero.
        return new ObservationPublisher(storePort.state(), storePort.observations(),
                engine::recordCount, new StoreBackedSinkPositions(storePort), engine::frontierGaps);
    }

    @Bean
    ConvergenceDriver convergenceDriver(
            PipelineConverger pipelineConverger, StorePort storePort, ObservationPublisher observationPublisher) {
        return new ConvergenceDriver(pipelineConverger, storePort.desired(), observationPublisher);
    }
}
