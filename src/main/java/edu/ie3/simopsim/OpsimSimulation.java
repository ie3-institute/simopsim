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
import edu.ie3.simona.api.data.model.em.SetPoint;
import edu.ie3.simona.api.mapping.DataType;
import edu.ie3.simona.api.mapping.ExtEntityMapping;
import edu.ie3.simona.api.ontology.em.EmCompletion;
import edu.ie3.simona.api.simulation.ExtCoSimFramework;
import edu.ie3.simona.api.simulation.ExtCoSimulation;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class OpsimSimulation extends ExtCoSimulation<InitializationData> {

  private final long stepSize;

  private final ExtEmDataConnection extEmDataConnection;
  private final ExtResultDataConnection extResultDataConnection;

  public OpsimSimulation(
      String simulationName,
      ExtCoSimFramework<InitializationData> extCoSimFramework,
      ExtEntityMapping mapping) {
    super(simulationName, extCoSimFramework);

    try {
      InitializationData.SimulatorData data = getInitData(InitializationData.SimulatorData.class);
      this.stepSize = data.stepSize();
      log.info("Step size: {}", stepSize);

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
  protected long initialize() {
    log.info(
        "+++++++++++++++++++++++++++ initialization of the external simulation +++++++++++++++++++++++++++");
    return 0L;
  }

  @Override
  public ExtOutputContainer handleExternalData(ExtInputContainer inputData)
      throws InterruptedException {
    long tick = inputData.getTick();
    long nextTick = determineNextTick(tick);
    OptionalLong maybeNextTick = OptionalLong.of(nextTick);

    log.info("Get data from OpSim.");
    Map<UUID, SetPoint> emSetPoints = inputData.extractSetPoints();

    log.info("Sending em set points to SIMONA.");
    extEmDataConnection.sendEmData(tick, emSetPoints, log);

    OptionalLong nextEmTick =
        extEmDataConnection.receiveWithType(EmCompletion.class).maybeNextTick();
    log.info("Next em tick: {}", nextEmTick);
    maybeNextTick = getNextTickOption(maybeNextTick, nextEmTick);
    log.info("Next SIMONA tick: {}", maybeNextTick);

    log.info("Waiting for data from SIMONA.");
    Map<UUID, List<ResultEntity>> resultsToBeSend =
        extResultDataConnection.requestResults(tick, true);
    ExtOutputContainer outputContainer = new ExtOutputContainer(tick, maybeNextTick);
    outputContainer.addResults(resultsToBeSend);

    return outputContainer;
  }

  @Override
  public ExtOutputContainer handleNoExternalData(long tick) throws InterruptedException {
    extEmDataConnection.simulateInternal(tick);
    log.info("Simulate internal for tick: {}", tick);
    OptionalLong nextEmTick = extEmDataConnection.receiveWithType(EmCompletion.class).maybeNextTick();

    return new ExtOutputContainer(tick, getNextTickOption(OptionalLong.of(tick), nextEmTick));
  }

  @Override
  public OptionalLong handleSimonaIsBehind(long tick, long extTick) throws InterruptedException {
    extEmDataConnection.simulateInternal(tick);
    OptionalLong nextEmTick = extEmDataConnection.receiveWithType(EmCompletion.class).maybeNextTick();

    log.info("Simulate internal for tick: {}. Next em tick: {}", tick, nextEmTick);

    return getNextTickOption(OptionalLong.of(extTick), nextEmTick);
  }

  @Override
  public void finishSimulation(long tick) {
    // not needed currently
  }

  @Override
  public long determineNextTick(long tick) {
    return tick + stepSize;
  }

  @Override
  public boolean continueActivity(long tick) {
    // false, since we don't need to loop the activity.
    return false;
  }
}
