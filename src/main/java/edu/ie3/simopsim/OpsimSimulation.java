/*
 * © 2024. TU Dortmund University,
 * Institute of Energy Systems, Energy Efficiency and Energy Economics,
 * Research group Distribution grid planning and operation
 */

package edu.ie3.simopsim;

import edu.ie3.datamodel.exceptions.SourceException;
import edu.ie3.simona.api.data.connection.ExtDataConnection;
import edu.ie3.simona.api.data.connection.ExtEmDataConnection;
import edu.ie3.simona.api.data.connection.ExtEmDataConnection.EmMode;
import edu.ie3.simona.api.data.connection.ExtResultDataConnection;
import edu.ie3.simona.api.data.container.ExtInputContainer;
import edu.ie3.simona.api.data.model.em.EmSetPoint;
import edu.ie3.simona.api.mapping.DataType;
import edu.ie3.simona.api.mapping.ExtEntityMapping;
import edu.ie3.simona.api.simulation.ExtCoSimulation;
import edu.ie3.simona.api.simulation.mapping.ExtEntityEntry;
import edu.ie3.simona.api.simulation.mapping.ExtEntityMappingSource;
import java.nio.file.Path;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpsimSimulation extends ExtCoSimulation {

  protected static final Logger log = LoggerFactory.getLogger(OpsimSimulation.class);

  protected final SimonaProxy simonaProxy;

  protected final ExtEntityMapping mapping;

  protected final int stepSize;

  private final ExtEmDataConnection extEmDataConnection;
  protected final ExtResultDataConnection extResultDataConnection;

  protected OpsimSimulation(String simulationName, String urlToOpsim, Path mappingPath) {
    this(simulationName, "SimonaProxy", urlToOpsim, mappingPath, 900);
  }

  protected OpsimSimulation(
      String simulationName,
      String extSimulatorName,
      String urlToOpsim,
      Path mappingPath,
      int stepSize) {
    super(simulationName, extSimulatorName);
    this.stepSize = stepSize;

    try {
      this.mapping = ExtEntityMappingSource.fromFile(mappingPath);
    } catch (SourceException e) {
      throw new RuntimeException(e);
    }

    this.extEmDataConnection =
        buildEmConnection(
            mapping.getEntries(DataType.EXT_EM_INPUT).stream().map(ExtEntityEntry::uuid).toList(),
            EmMode.BASE,
            Optional.empty(),
            log);

    this.simonaProxy = new SimonaProxy();
    simonaProxy.setConnectionToSimonaApi(queueToSimona, queueToExt, mapping);

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

    SimopsimUtils.runSimopsim(simonaProxy, urlToOpsim);
    log.info("Connected to: {}", urlToOpsim);
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
      ExtInputContainer container = simonaProxy.queueToSIMONA.takeContainer();

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
