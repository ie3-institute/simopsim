/*
 * © 2024. TU Dortmund University,
 * Institute of Energy Systems, Energy Efficiency and Energy Economics,
 * Research group Distribution grid planning and operation
 */

package edu.ie3.simopsim;

import edu.ie3.datamodel.exceptions.SourceException;
import edu.ie3.datamodel.models.value.Value;
import edu.ie3.simona.api.data.ExtInputDataContainer;
import edu.ie3.simona.api.data.results.ExtResultDataConnection;
import edu.ie3.simona.api.simulation.ExtCoSimulation;
import edu.ie3.simona.api.simulation.mapping.ExtEntityMapping;
import edu.ie3.simona.api.simulation.mapping.ExtEntityMappingSource;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class OpsimSimulation extends ExtCoSimulation {

  protected static final Logger log = LoggerFactory.getLogger(OpsimSimulation.class);

  protected final SimonaProxy simonaProxy;

  protected final ExtEntityMapping mapping;

  protected final int stepSize;

  protected final ExtResultDataConnection extResultDataConnection;

  protected OpsimSimulation(String simulationName, Path mappingPath) {
    this(simulationName, "SimonaProxy", mappingPath, 900);
  }

  protected OpsimSimulation(
      String simulationName, String extSimulatorName, Path mappingPath, int stepSize) {
    super(simulationName, extSimulatorName);
    this.stepSize = stepSize;

    try {
      this.mapping = ExtEntityMappingSource.fromFile(mappingPath);
    } catch (SourceException e) {
      throw new RuntimeException(e);
    }

    this.simonaProxy = new SimonaProxy();
    simonaProxy.setConnectionToSimonaApi(
        dataQueueExtCoSimulatorToSimonaApi, dataQueueSimonaApiToExtCoSimulator);

    this.extResultDataConnection = buildResultConnection(mapping, log);
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
      ExtInputDataContainer rawEmData = simonaProxy.dataQueueOpsimToSimona.takeData();
      if (rawEmData.getTick() != tick) {
        throw new RuntimeException(String.format("OpSim provided input data for tick %d, but SIMONA expects input data for tick %d", rawEmData.getTick(), tick));
      }
      Map<String, Value> inputMap = rawEmData.getSimonaInputMap();
      sendToSimona(tick, inputMap, rawEmData.getMaybeNextTick());
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }

    try {
      sendDataToExt(extResultDataConnection, tick, Optional.of(nextTick), log);

      log.info(
          "***** External simulation for tick {} completed. Next simulation tick = {} *****",
          tick,
          nextTick);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
    return Optional.of(nextTick);
  }

  protected abstract void sendToSimona(
      long tick, Map<String, Value> inputMap, Optional<Long> maybeNextTick);
}
