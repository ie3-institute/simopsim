/*
 * © 2024. TU Dortmund University,
 * Institute of Energy Systems, Energy Efficiency and Energy Economics,
 * Research group Distribution grid planning and operation
 */

package edu.ie3.simopsim;

import edu.ie3.datamodel.models.result.ResultEntity;
import edu.ie3.simona.api.data.connection.ExtDataConnection;
import edu.ie3.simona.api.data.connection.ExtEmDataConnection;
import edu.ie3.simona.api.data.connection.ExtEmDataConnection.EmMode;
import edu.ie3.simona.api.data.connection.ExtResultDataConnection;
import edu.ie3.simona.api.data.container.ExtInputContainer;
import edu.ie3.simona.api.data.container.ExtOutputContainer;
import edu.ie3.simona.api.data.model.em.EmSetPoint;
import edu.ie3.simona.api.mapping.DataType;
import edu.ie3.simona.api.mapping.ExtEntityMapping;
import edu.ie3.simona.api.ontology.em.EmCompletion;
import edu.ie3.simona.api.simulation.ExtCoSimulation;
import edu.ie3.simopsim.initialization.InitializationData;
import edu.ie3.simopsim.initialization.InitializationQueue;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OpsimSimulation extends ExtCoSimulation {

  private static final Logger log = LoggerFactory.getLogger(OpsimSimulation.class);

  private final long stepSize;
  private long lastTick = -1;
  private long nextExtTick = 0L;

  private final ExtEmDataConnection extEmDataConnection;
  private final ExtResultDataConnection extResultDataConnection;

  public OpsimSimulation(
      String simulationName, InitializationQueue queue, ExtEntityMapping mapping) {
    super(simulationName, "SimonaProxy");

    try {
      InitializationData.SimulatorData data = queue.take(InitializationData.SimulatorData.class);
      this.stepSize = data.stepSize() / 1000;
      data.setConnectionToSimonaApi().accept(queueToSimona, queueToExt);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }

    this.extEmDataConnection = buildEmConnection(mapping.getAssets(DataType.EM), EmMode.BASE, log);

    // result data connection
    List<UUID> results = mapping.getAssets(DataType.RESULT);
    this.extResultDataConnection = !results.isEmpty() ? new ExtResultDataConnection(results) : null;
  }

  @Override
  public Set<ExtDataConnection> getDataConnections() {
    return Stream.of(extEmDataConnection, extResultDataConnection)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
  }

  @Override
  protected Long initialize() {
    log.info(
        "+++++++++++++++++++++++++++ initialization of the external simulation +++++++++++++++++++++++++++");
    return 0L;
  }

  @Override
  protected Optional<Long> doActivity(long tick) {
    log.info("+++++ External simulation triggered for tick {} +++++", tick);

    long nextTick = tick + stepSize;
    Optional<Long> maybeNextTick = Optional.of(nextExtTick);

    try {
      if (tick < nextExtTick && tick > lastTick) {
        extEmDataConnection.simulateInternal(tick);
        log.info("Simulate internal for tick: {}", tick);
      } else if (tick == lastTick) {
        return maybeNextTick;
      } else {
        log.info("Get data from OpSim.");
        ExtInputContainer container = queueToSimona.takeContainer();
        Map<UUID, EmSetPoint> emSetPoints = container.extractSetPoints();

        log.info("Sending em set points to SIMONA.");
        extEmDataConnection.sendEmData(tick, emSetPoints, log);

        log.info("Waiting for data from SIMONA.");

        Map<UUID, List<ResultEntity>> resultsToBeSend = extResultDataConnection.requestResults(tick);
        ExtOutputContainer outputContainer = new ExtOutputContainer(tick, maybeNextTick);
          outputContainer.addResults(resultsToBeSend);
        queueToExt.queueData(outputContainer);

        log.info(
            "***** External simulation for tick {} completed. Next simulation tick = {} *****",
            tick,
            nextTick);

        nextExtTick = nextTick;
      }

      Optional<Long> nextEmTick =
          extEmDataConnection.receiveWithType(EmCompletion.class).maybeNextTick();
      log.info("Next em tick: {}", nextEmTick);

      if (nextEmTick.isPresent()) {
        long emTick = nextEmTick.get();

        if (emTick != tick && emTick < nextExtTick) {
          maybeNextTick = nextEmTick;
        }
      }

    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }

    lastTick = tick;
    return maybeNextTick;
  }
}
