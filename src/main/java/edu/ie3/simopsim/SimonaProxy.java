/*
 * © 2024. TU Dortmund University,
 * Institute of Energy Systems, Energy Efficiency and Energy Economics,
 * Research group Distribution grid planning and operation
 */

package edu.ie3.simopsim;

import de.fhg.iee.opsim.DAO.AssetComparator;
import de.fhg.iee.opsim.DAO.ProxyConfigDAO;
import de.fhg.iee.opsim.abstracts.ConservativeSynchronizedProxy;
import de.fhg.iee.opsim.interfaces.ClientInterface;
import de.fhg.iwes.opsim.datamodel.generated.asset.Asset;
import de.fhg.iwes.opsim.datamodel.generated.assetoperator.AssetOperator;
import de.fhg.iwes.opsim.datamodel.generated.realtimedata.OpSimAggregatedSetPoints;
import de.fhg.iwes.opsim.datamodel.generated.realtimedata.OpSimMessage;
import de.fhg.iwes.opsim.datamodel.generated.scenarioconfig.ScenarioConfig;
import edu.ie3.simona.api.data.ExtDataContainerQueue;
import edu.ie3.simona.api.data.container.ExtInputContainer;
import edu.ie3.simona.api.data.container.ExtOutputContainer;
import edu.ie3.simona.api.data.model.em.EmSetPoint;
import edu.ie3.simona.api.mapping.ExtEntityMapping;
import edu.ie3.simona.api.simulation.ExtCoSimFramework;
import java.util.*;
import javax.xml.bind.JAXBException;
import org.apache.logging.log4j.Logger;

/** Class that extends the Proxy interface of OPSIM */
public final class SimonaProxy extends ConservativeSynchronizedProxy
    implements ExtCoSimFramework<InitializationData> {

  private Logger logger;
  private ClientInterface cli;
  private String componentDescription = "SIMONA";

  private long delta = -1L;
  private long lastTimeStep = 0L;
  private long startTime;

  private long initTimeStep = 0L;
  private final Set<Asset> readable = new TreeSet<>(new AssetComparator());
  private final Set<Asset> writable = new TreeSet<>(new AssetComparator());

  private Queue<InitializationData> queue;
  private final TickConverter converter = new TickConverter(0.001);

  private final ExtEntityMapping mapping;
  private final Map<UUID, List<UUID>> nodeToParticipants;
  private final Map<UUID, UUID> participantToNode;

  private final ExtDataContainerQueue<ExtInputContainer> inputQueue = new ExtDataContainerQueue<>();
  private final ExtDataContainerQueue<ExtOutputContainer> outputQueue =
      new ExtDataContainerQueue<>();

  public SimonaProxy(
      ExtEntityMapping mapping,
      Map<UUID, List<UUID>> nodeToParticipants,
      Map<UUID, UUID> participantToNode) {
    this.mapping = mapping;
    this.nodeToParticipants = nodeToParticipants;
    this.participantToNode = participantToNode;
  }

  @Override
  public void SetUp(String componentDescription, ClientInterface client, Logger logger) {
    this.logger = logger;
    this.cli = client;
    this.componentDescription = componentDescription;
  }

  @Override
  public boolean initProxy(ProxyConfigDAO config) {
    // logger.info("Proxy {} is initialized!", componentDescription);
    this.setNrOfComponents(config.getNrOfComponents());
    return true;
  }

  @Override
  public boolean initComponent(String componentConfig) {
    if (componentConfig != null && !componentConfig.isEmpty()) {
      try {
        ScenarioConfig scenarioConfig = ScenarioConfigReader.read(componentConfig);

        List<AssetOperator> operators =
            scenarioConfig.getAssetOperator().stream()
                .filter(ao -> ao.getAssetOperatorName().equals(this.getComponentName()))
                .toList();

        for (AssetOperator ao : operators) {
          this.readable.addAll(ao.getReadableAssets());
          this.writable.addAll(ao.getControlledAssets());
          this.delta = ao.getOperationInterval();
        }

        this.initTimeStep = cli.getClock().getActualTime().getMillis();
        this.lastTimeStep = initTimeStep;
        this.startTime = cli.getCurrentSimulationTime().getMillis();

        // necessary to wait for SIMONA
        queue.add(new InitializationData.SimulatorData(converter.toSimonaTick(delta)));

        return true;
      } catch (JAXBException ex) {
        logger.error("Problem with the Config Data not right format and or incomplete. ", ex);
        return false;
      }
    } else {
      return false;
    }
  }

  @Override
  public Queue<OpSimMessage> step(Queue<OpSimMessage> inputFromClient, long timeStep) {
    logger.info(
        "{} step call at simulation time = {} present timezone = {}",
        componentDescription,
        cli.getClock().getActualTime().getMillis(),
        cli.getCurrentSimulationTime());

    long currentTick = cli.getCurrentSimulationTime().getMillis() - startTime;

    if (timeStep == this.initTimeStep
        || (timeStep < this.lastTimeStep + this.delta && timeStep != this.lastTimeStep)) {
      return null;
    } else {
      // Get message from external
      this.lastTimeStep = timeStep;

      try {
        logger.info("Received messages for {}", this.cli.getCurrentSimulationTime().toString());
        List<EmSetPoint> dataForSimona = SimopsimUtils.createEmSetPoints(inputFromClient, mapping);
        ExtInputContainer container = new ExtInputContainer(currentTick);
        dataForSimona.forEach(container::addSetPoint);
        inputQueue.queueData(container);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }

      // --------------------------------------------------------------------------------------------------
      // Trigger SIMONA to provide result

      try {
        logger.info("Wait for results from SIMONA!");
        // Wait for results from SIMONA!
        ExtOutputContainer results = outputQueue.takeContainer();
        logger.info("Received results from SIMONA!");

        logger.debug(
            "Send Aggregated SetPoints for {}", this.cli.getCurrentSimulationTime().toString());
        List<OpSimAggregatedSetPoints> osmAggSetPoints =
            SimopsimUtils.createSimopsimOutputList(
                writable,
                cli.getClock().getActualTime().plus(delta).getMillis(),
                results,
                mapping,
                nodeToParticipants,
                participantToNode);

        printMsg(osmAggSetPoints);
        sendToOpSim(osmAggSetPoints);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
      return inputFromClient;
    }
  }

  @Override
  public String getComponentName() {
    return componentDescription;
  }

  @Override
  public void stop() {
    logger.info("stop() received.");
  }

  // -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-

  private void printMsg(List<OpSimAggregatedSetPoints> osmAggSetPoints) {
    var simulationTime = this.cli.getCurrentSimulationTime();

    System.out.println();
    System.out.println("--- Produced OpSim Messages --------------------------------------------");
    osmAggSetPoints.forEach(msg -> SimopsimUtils.printMessage(msg, simulationTime));
    System.out.println("------------------------------------------------------------------------");
    System.out.println();
  }

  private <T extends OpSimMessage> void sendToOpSim(List<T> inputFromComponent) {
    if (inputFromComponent.isEmpty()) {
      logger.info("The component has not generated output to send.");
    } else {
      for (OpSimMessage msg : inputFromComponent) {
        cli.pushToMq(cli.getProxy(), msg);
      }
      logger.info("Results sent: {}", cli.getClock().getActualTime().toDateTimeISO());
    }
  }

  @Override
  public String getName() {
    return "SimonaProxy";
  }

  @Override
  public void setInitDataQueue(Queue<InitializationData> queue) {
    this.queue = queue;
  }

  @Override
  public Status getStatus(long simonaTick) throws InterruptedException {
    long extTick = converter.toSimonaTick(inputQueue.takeData(ExtInputContainer::getTick));

    if (simonaTick == extTick) {
      return new HasData(inputQueue.takeContainer());
    } else if (simonaTick < extTick) {
      return new SimonaIsBehind(extTick);
    } else {
      return new SimonaIsAhead();
    }
  }

  @Override
  public void provideOutputData(ExtOutputContainer extOutputContainer) {
    try {
      this.outputQueue.queueData(extOutputContainer);
      logger.info("Provided OpSim with results.");
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void goToNextTick(long tick) {
    throw new IllegalStateException("This should not be called");
  }
}
