/*
 * © 2024. TU Dortmund University,
 * Institute of Energy Systems, Energy Efficiency and Energy Economics,
 * Research group Distribution grid planning and operation
 */

package edu.ie3.simopsim;

import static de.fhg.iwes.opsim.datamodel.generated.realtimedata.MeasurementValueType.*;

import de.fhg.iee.opsim.DAO.AssetComparator;
import de.fhg.iee.opsim.DAO.ProxyConfigDAO;
import de.fhg.iee.opsim.abstracts.ConservativeSynchronizedProxy;
import de.fhg.iee.opsim.interfaces.ClientInterface;
import de.fhg.iwes.opsim.datamodel.generated.asset.Asset;
import de.fhg.iwes.opsim.datamodel.generated.assetoperator.AssetOperator;
import de.fhg.iwes.opsim.datamodel.generated.realtimedata.MeasurementValueType;
import de.fhg.iwes.opsim.datamodel.generated.realtimedata.OpSimAggregatedSetPoints;
import de.fhg.iwes.opsim.datamodel.generated.realtimedata.OpSimMessage;
import de.fhg.iwes.opsim.datamodel.generated.scenarioconfig.ScenarioConfig;
import edu.ie3.simona.api.data.ExtDataContainerQueue;
import edu.ie3.simona.api.data.container.ExtInputContainer;
import edu.ie3.simona.api.data.container.ExtResultContainer;
import edu.ie3.simona.api.data.model.em.EmSetPoint;
import edu.ie3.simona.api.mapping.DataType;
import edu.ie3.simona.api.mapping.ExtEntityEntry;
import edu.ie3.simona.api.mapping.ExtEntityMapping;
import edu.ie3.simopsim.initialization.InitializationData;
import edu.ie3.simopsim.initialization.InitializationQueue;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.xml.bind.JAXBException;
import org.apache.logging.log4j.Logger;

/** Class that extends the Proxy interface of OPSIM */
public final class SimonaProxy extends ConservativeSynchronizedProxy {

  private Logger logger;
  private ClientInterface cli;
  private String componentDescription = "SIMONA";

  private long delta = -1L;
  private long lastTimeStep = 0L;

  private long initTimeStep = 0L;
  private final Set<Asset> readable = new TreeSet<>(new AssetComparator());
  private final Set<Asset> writable = new TreeSet<>(new AssetComparator());

  private final InitializationQueue queue;

  public ExtDataContainerQueue<ExtInputContainer> queueToSIMONA;
  public ExtDataContainerQueue<ExtResultContainer> queueToOpSim;
  private ExtEntityMapping mapping;

  public SimonaProxy(InitializationQueue queue) {
    this.queue = queue;
  }

  public void setConnectionToSimonaApi(
      ExtDataContainerQueue<ExtInputContainer> queueToSIMONA,
      ExtDataContainerQueue<ExtResultContainer> queueToOpSim) {
    this.queueToSIMONA = queueToSIMONA;
    this.queueToOpSim = queueToOpSim;
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

        List<ExtEntityEntry> entries = new ArrayList<>();

        for (AssetOperator ao : operators) {
          Function<List<Asset>, Map<String, Asset>> toMap =
              list -> list.stream().collect(Collectors.toMap(Asset::getGridAssetId, a -> a));

          Map<String, Asset> idToReadableAsset = toMap.apply(ao.getReadableAssets());
          Map<String, Asset> idToControlledAsset = toMap.apply(ao.getControlledAssets());

          Set<String> both =
              idToReadableAsset.keySet().stream()
                  .filter(idToControlledAsset::containsKey)
                  .collect(Collectors.toSet());

          // handle assets that have readable and writable attributes
          both.forEach(
              assetId -> {
                // remove needed to prevent second execution
                Asset readable = idToReadableAsset.remove(assetId);
                Asset controlled = idToControlledAsset.remove(assetId);

                DataType type =
                    getDataType(
                        readable.getMeasurableQuantities(), controlled.getMeasurableQuantities());

                entries.add(new ExtEntityEntry(UUID.fromString(assetId), assetId, type));

                this.readable.add(readable);
                this.writable.add(controlled);
              });

          // handle readable assets
          idToReadableAsset.forEach(
              (assetId, asset) -> {
                UUID uuid = UUID.fromString(assetId);

                entries.add(
                    new ExtEntityEntry(
                        uuid, assetId, getDataType(asset.getMeasurableQuantities())));
                this.readable.add(asset);
              });

          // handle controlled assets
          idToControlledAsset.forEach(
              (assetId, asset) -> {
                UUID uuid = UUID.fromString(assetId);

                entries.add(
                    new ExtEntityEntry(
                        uuid, assetId, getDataType(asset.getMeasurableQuantities())));
                this.writable.add(asset);
              });

          this.delta = ao.getOperationInterval();
        }

        this.initTimeStep = cli.getClock().getActualTime().getMillis();
        this.lastTimeStep = initTimeStep;

        this.mapping = new ExtEntityMapping(entries);

        logger.info(
            "Component {}, got Readables: {}, Writables: {} and Delta: {}",
            new Object[] {
              this.componentDescription, this.readable.size(), this.writable.size(), this.delta
            });

        queue.put(
            new InitializationData.SimulatorData(delta, mapping, this::setConnectionToSimonaApi));

        return true;
      } catch (JAXBException ex) {
        logger.error("Problem with the Config Data not right format and or incomplete. ", ex);
        return false;
      } catch (InterruptedException e) {
        logger.error("Could not send init data to SIMONA.", e);
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

    if (timeStep == this.initTimeStep
        || (timeStep < this.lastTimeStep + this.delta && timeStep != this.lastTimeStep)) {
      return null;
    } else {
      // Get message from external
      this.lastTimeStep = timeStep;
      try {
        logger.info("Received messages for {}", this.cli.getCurrentSimulationTime().toString());
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
            "Send Aggregated SetPoints for {}", this.cli.getCurrentSimulationTime().toString());
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

  public static DataType getDataType(List<MeasurementValueType> measurementValueTypes) {
    throw new IllegalArgumentException(
        "Could not find valid data type from given measurementValueTypes: "
            + measurementValueTypes);
  }

  public static DataType getDataType(
      List<MeasurementValueType> readableMeasurementValueType,
      List<MeasurementValueType> controlledMeasurementValueTypes) {

    boolean isEM =
        readableMeasurementValueType.size() == 1
            && readableMeasurementValueType.contains(FLEXIBILITY_SCHEDULE)
            && (controlledMeasurementValueTypes.contains(ACTIVE_POWER)
                || controlledMeasurementValueTypes.contains(REACTIVE_POWER));

    if (isEM) {
      return DataType.EXT_EM_INPUT;
    }

    throw new IllegalArgumentException(
        "Could not find valid data type from given readable measurementValueTypes: "
            + readableMeasurementValueType
            + " and controlledMeasurementValueTypes: "
            + controlledMeasurementValueTypes);
  }
}
