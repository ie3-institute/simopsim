/*
 * © 2024. TU Dortmund University,
 * Institute of Energy Systems, Energy Efficiency and Energy Economics,
 * Research group Distribution grid planning and operation
 */

package edu.ie3.simopsim;

import edu.ie3.simona.api.data.connection.ExtDataConnection;
import edu.ie3.simona.api.data.connection.ExtEmDataConnection;
import edu.ie3.simona.api.data.connection.ExtEmDataConnection.EmMode;
import edu.ie3.simona.api.data.connection.ExtResultDataConnection;
import edu.ie3.simona.api.data.container.ExtInputContainer;
import edu.ie3.simona.api.data.model.em.EmSetPoint;
import edu.ie3.simona.api.mapping.DataType;
import edu.ie3.simona.api.mapping.ExtEntityEntry;
import edu.ie3.simona.api.mapping.ExtEntityMapping;
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

  private final ExtEmDataConnection extEmDataConnection;
  private final ExtResultDataConnection extResultDataConnection;

  public OpsimSimulation(
      String simulationName, InitializationQueue queue, ExtEntityMapping mapping) {
    super(simulationName, "SimonaProxy");

    try {
      InitializationData.SimulatorData data = queue.take(InitializationData.SimulatorData.class);
      this.stepSize = data.stepSize();
      data.setConnectionToSimonaApi().accept(queueToSimona, queueToExt);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }

    this.extEmDataConnection =
        buildEmConnection(
            mapping.getEntries(DataType.EM).stream().map(ExtEntityEntry::uuid).toList(),
            EmMode.BASE,
            log);

    // result data connection
    Map<DataType, List<UUID>> resultInput = new HashMap<>();
    List<UUID> results =
        mapping.getEntries(DataType.RESULT).stream().map(ExtEntityEntry::uuid).toList();

    this.extResultDataConnection =
        !results.isEmpty() ? buildResultConnection(resultInput, log) : null;
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

    try {
      ExtInputContainer container = queueToSimona.takeContainer();

      Optional<Long> maybeNextTick = container.getMaybeNextTick();

      Map<UUID, EmSetPoint> emSetPoints = container.extractSetPoints();

      sendEmSetPointsToSimona(extEmDataConnection, tick, emSetPoints, maybeNextTick, log);

      sendResultToExt(extResultDataConnection, tick, Optional.of(nextTick), log);

      log.info(
          "***** External simulation for tick {} completed. Next simulation tick = {} *****",
          tick,
          nextTick);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
    return Optional.of(nextTick);
  }
}
