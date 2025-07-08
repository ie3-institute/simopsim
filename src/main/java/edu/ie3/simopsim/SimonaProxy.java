/*
 * © 2024. TU Dortmund University,
 * Institute of Energy Systems, Energy Efficiency and Energy Economics,
 * Research group Distribution grid planning and operation
 */

package edu.ie3.simopsim;

import de.fhg.iee.opsim.DAO.AssetComparator;
import de.fhg.iee.opsim.DAO.ProxyConfigDAO;
import de.fhg.iee.opsim.abstracts.ConservativeSynchronizedProxy;
import de.fhg.iee.opsim.client.Client;
import de.fhg.iee.opsim.interfaces.ClientInterface;
import de.fhg.iwes.opsim.datamodel.generated.asset.Asset;
import de.fhg.iwes.opsim.datamodel.generated.assetoperator.AssetOperator;
import de.fhg.iwes.opsim.datamodel.generated.realtimedata.OpSimAggregatedSetPoints;
import de.fhg.iwes.opsim.datamodel.generated.realtimedata.OpSimMessage;
import de.fhg.iwes.opsim.datamodel.generated.scenarioconfig.ScenarioConfig;
import edu.ie3.simona.api.data.ExtDataContainerQueue;
import edu.ie3.simona.api.data.container.ExtInputContainer;
import edu.ie3.simona.api.data.container.ExtResultContainer;
import edu.ie3.simona.api.data.model.em.EmSetPoint;
import edu.ie3.simona.api.mapping.ExtEntityMapping;
import java.io.IOException;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import javax.xml.bind.JAXBException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Class that extends the Proxy interface of OPSIM */
public class SimonaProxy extends ConservativeSynchronizedProxy {

  protected Logger logger;
  protected ClientInterface cli;
  protected String componentDescription = "SIMONA";

  private long delta = -1L;
  private long lastTimeStep = 0L;

  private long initTimeStep = 0L;
  private final Set<Asset> readable = new TreeSet<>(new AssetComparator());
  private final Set<Asset> writable = new TreeSet<>(new AssetComparator());

  public ExtDataContainerQueue<ExtInputContainer> queueToSIMONA;
  public ExtDataContainerQueue<ExtResultContainer> queueToOpSim;
  private ExtEntityMapping mapping;

  public SimonaProxy() {
    try {
      Logger logger = LogManager.getLogger(Client.class);
      Client client = new Client(logger);
      this.logger = logger;
      this.cli = client;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public ClientInterface getCli() {
    return cli;
  }

  public void setConnectionToSimonaApi(
      ExtDataContainerQueue<ExtInputContainer> queueToSIMONA,
      ExtDataContainerQueue<ExtResultContainer> queueToOpSim,
      ExtEntityMapping mapping) {
    this.queueToSIMONA = queueToSIMONA;
    this.queueToOpSim = queueToOpSim;
    this.mapping = mapping;
  }

  @Override
  public void SetUp(String componentDescription, ClientInterface client, Logger logger) {
    this.logger = logger;
    this.cli = client;
    this.componentDescription = componentDescription;
  }

  @Override
  public boolean initProxy(ProxyConfigDAO config) {
    logger.info("Proxy {} is initialized!", componentDescription);
    this.setNrOfComponents(config.getNrOfComponents());
    return true;
  }

  @Override
  public boolean initComponent(String componentConfig) {
    if (componentConfig != null && !componentConfig.isEmpty()) {
      try {
        ScenarioConfig scenarioConfig = ScenarioConfigReader.read(componentConfig);

        for (AssetOperator ao : scenarioConfig.getAssetOperator()) {
          if (ao.getAssetOperatorName().equals(this.getComponentName())) {
            this.readable.addAll(ao.getReadableAssets());
            this.writable.addAll(ao.getControlledAssets());
            this.delta = ao.getOperationInterval();
          }
        }

        this.initTimeStep = cli.getClock().getActualTime().getMillis();
        this.lastTimeStep = initTimeStep;

        this.logger.info(
            "Component {}, got Readables: {}, Writables: {} and Delta: {}",
            new Object[] {
              this.componentDescription, this.readable.size(), this.writable.size(), this.delta
            });
        return true;
      } catch (JAXBException ex) {
        this.logger.error("Problem with the Config Data not right format and or incomplete. ", ex);
        return false;
      }
    } else {
      return false;
    }
  }

  @Override
  public Queue<OpSimMessage> step(Queue<OpSimMessage> inputFromClient, long timeStep) {
    logger.info(
        componentDescription
            + " step call at simulation time = "
            + cli.getClock().getActualTime().getMillis()
            + " present timezone = "
            + cli.getCurrentSimulationTime());
    if (timeStep == this.initTimeStep
        || (timeStep < this.lastTimeStep + this.delta && timeStep != this.lastTimeStep)) {
      return null;
    } else {
      // Get message from external
      this.lastTimeStep = timeStep;
      try {
        logger.info("Received messages for " + this.cli.getCurrentSimulationTime().toString());
        List<EmSetPoint> dataForSimona = SimopsimUtils.createEmSetPoints(inputFromClient, mapping);
        ExtInputContainer inputDataContainer = new ExtInputContainer(0L);
        dataForSimona.forEach(inputDataContainer::addSetPoint);
        queueToSIMONA.queueData(inputDataContainer);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }

      // --------------------------------------------------------------------------------------------------

      // Trigger SIMONA to provide result

      try {
        logger.info("Wait for results from SIMONA!");
        // Wait for results from SIMONA!
        ExtResultContainer results = queueToOpSim.takeContainer();
        logger.info("Received results from SIMONA!");

        logger.debug(
            "Send Aggregated SetPoints for " + this.cli.getCurrentSimulationTime().toString());
        List<OpSimAggregatedSetPoints> osmAggSetPoints =
            SimopsimUtils.createSimopsimOutputList(
                writable, cli.getClock().getActualTime().plus(delta).getMillis(), results, mapping);

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

  protected void printMsg(List<OpSimAggregatedSetPoints> osmAggSetPoints) {
    var simulationTime = this.cli.getCurrentSimulationTime();

    System.out.println();
    System.out.println("--- Produced OpSim Messages --------------------------------------------");
    osmAggSetPoints.forEach(msg -> SimopsimUtils.printMessage(msg, simulationTime));
    System.out.println("------------------------------------------------------------------------");
    System.out.println();
  }

  protected <T extends OpSimMessage> void sendToOpSim(List<T> inputFromComponent) {
    if (inputFromComponent.isEmpty()) {
      logger.info("The component has not generated output to send.");
    } else {
      for (OpSimMessage msg : inputFromComponent) {
        cli.pushToMq(cli.getProxy(), msg);
      }
      logger.info("Results sent: {}", cli.getClock().getActualTime().toDateTimeISO());
    }
  }
}
