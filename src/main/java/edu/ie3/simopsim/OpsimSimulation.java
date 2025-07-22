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
import edu.ie3.simona.api.mapping.ExtEntityMapping;
import edu.ie3.simona.api.simulation.ExtCoSimulation;
import edu.ie3.simona.api.mapping.ExtEntityEntry;
import edu.ie3.simopsim.initialization.InitializationData;
import edu.ie3.simopsim.initialization.InitializationQueue;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OpsimSimulation extends ExtCoSimulation {

  private static final Logger log = LoggerFactory.getLogger(OpsimSimulation.class);

  private final long stepSize;

  private final ExtEmDataConnection extEmDataConnection;
  private final ExtResultDataConnection extResultDataConnection;

  public OpsimSimulation(String simulationName, InitializationQueue queue) {
    super(simulationName, "SimonaProxy");

    ExtEntityMapping mapping;

    try {
      InitializationData.SimulatorData data = queue.take(InitializationData.SimulatorData.class);

      this.stepSize = data.stepSize();
      mapping = data.mapping();

      data.setConnectionToSimonaApi().accept(queueToSimona, queueToExt);

    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }

    this.extEmDataConnection =
        buildEmConnection(
            mapping.getEntries(DataType.EXT_EM_INPUT).stream().map(ExtEntityEntry::uuid).toList(),
            EmMode.BASE,
            log);

    Map<DataType, List<UUID>> map = new HashMap<>();
    List<UUID> participantResults =
        mapping.getEntries(DataType.EXT_PARTICIPANT_RESULT).stream()
            .map(ExtEntityEntry::uuid)
            .toList();
    List<UUID> gridResults =
        mapping.getEntries(DataType.EXT_GRID_RESULT).stream().map(ExtEntityEntry::uuid).toList();
    List<UUID> flexResults =
        mapping.getEntries(DataType.EXT_FLEX_OPTIONS_RESULT).stream()
            .map(ExtEntityEntry::uuid)
            .toList();

    map.put(DataType.EXT_PARTICIPANT_RESULT, participantResults);
    map.put(DataType.EXT_GRID_RESULT, gridResults);
    map.put(DataType.EXT_FLEX_OPTIONS_RESULT, flexResults);

    this.extResultDataConnection = buildResultConnection(map, log);
  }

  @Override
  public Set<ExtDataConnection> getDataConnections() {
    return Set.of(extEmDataConnection, extResultDataConnection);
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
