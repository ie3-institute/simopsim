package edu.ie3.simopsim;

import de.fhg.iee.opsim.DAO.AssetComparator;
import de.fhg.iee.opsim.DAO.ProxyConfigDAO;
import de.fhg.iee.opsim.abstracts.ConservativeSynchronizedProxy;
import de.fhg.iee.opsim.client.Client;
import de.fhg.iee.opsim.interfaces.ClientInterface;
import de.fhg.iwes.opsim.datamodel.dao.OpSimDataModelFileDao;
import de.fhg.iwes.opsim.datamodel.generated.asset.Asset;
import de.fhg.iwes.opsim.datamodel.generated.assetoperator.AssetOperator;
import de.fhg.iwes.opsim.datamodel.generated.realtimedata.OpSimAggregatedSetPoints;
import de.fhg.iwes.opsim.datamodel.generated.realtimedata.OpSimMessage;
import de.fhg.iwes.opsim.datamodel.generated.scenarioconfig.ScenarioConfig;
import edu.ie3.datamodel.models.value.Value;
import edu.ie3.simona.api.data.DataQueueExtSimulationExtSimulator;
import edu.ie3.simona.api.data.ExtInputDataContainer;
import edu.ie3.simona.api.data.results.ExtResultContainer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.xml.bind.JAXBException;
import java.io.IOException;
import java.util.*;

/**
 * Class that extends the Proxy interface of OPSIM
 */
public class SimonaProxy extends ConservativeSynchronizedProxy {

    protected Logger logger;
    protected ClientInterface cli;
    protected String componentDescription = "SIMONA";

    private long delta = -1L;
    private long lastTimeStep = 0L;

    private long initTimeStep = 0L;
    private final Set<Asset> readable = new TreeSet<>(new AssetComparator());
    private final Set<Asset> writable = new TreeSet<>(new AssetComparator());

    public DataQueueExtSimulationExtSimulator<ExtInputDataContainer> dataQueueOpsimToSimona;
    public DataQueueExtSimulationExtSimulator<ExtResultContainer> dataQueueSimonaToOpsim;

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
            DataQueueExtSimulationExtSimulator<ExtInputDataContainer> dataQueueExtCoSimulatorToSimonaApi,
            DataQueueExtSimulationExtSimulator<ExtResultContainer> dataQueueSimonaApiToExtCoSimulator) {
        this.dataQueueOpsimToSimona = dataQueueExtCoSimulatorToSimonaApi;
        this.dataQueueSimonaToOpsim = dataQueueSimonaApiToExtCoSimulator;
    }

    @Override
    public void SetUp(String componentDescription, ClientInterface client, Logger logger) {
        this.logger = logger;
        this.cli = client;
        this.componentDescription = componentDescription;
    }

    @Override
    public boolean initProxy(ProxyConfigDAO config) {
        logger.info(
                "Proxy {} is initialized!",
                componentDescription
        );
        this.setNrOfComponents(config.getNrOfComponents());
        return true;
    }

    @Override
    public boolean initComponent(String componentConfig) {
        if (componentConfig != null && !componentConfig.isEmpty()) {
            try {
                OpSimDataModelFileDao opsFile = new OpSimDataModelFileDao();
                ScenarioConfig scenarioConfig = opsFile.read(componentConfig);

                for (AssetOperator ao : scenarioConfig.getAssetOperator()) {
                    if (ao.getAssetOperatorName().equals(this.getComponentName())) {
                        this.readable.addAll(ao.getReadableAssets());
                        this.writable.addAll(ao.getControlledAssets());
                        this.delta = ao.getOperationInterval();
                    }
                }
                this.initTimeStep = cli.getClock().getActualTime().getMillis();
                this.lastTimeStep = cli.getClock().getActualTime().getMillis();

                this.logger.info("Component {}, got Readables: {}, Writables: {} and Delta: {}", new Object[]{this.componentDescription, this.readable.size(), this.writable.size(), this.delta});
                return true;
            } catch (JAXBException var6) {
                this.logger.error("Problem with the Config Data not right format and or incomplete. ", var6);
                return false;
            }
        } else {
            return false;
        }
    }

    @Override
    public Queue<OpSimMessage> step(Queue<OpSimMessage> inputFromClient, long timeStep) {
        logger.info(
                componentDescription + " step call at simulation time = " + cli.getClock().getActualTime().getMillis() + " present timezone = " + cli.getCurrentSimulationTime());
        if (timeStep == this.initTimeStep || (timeStep < this.lastTimeStep + this.delta && timeStep != this.lastTimeStep)) {
            return null;
        } else {
            // Get message from Netzbetriebsfuehrung
            this.lastTimeStep = timeStep;
            try {
                logger.info("Received messages for " + this.cli.getCurrentSimulationTime().toString());
                Map<String, Value> dataForSimona = SimopsimUtils.createInputMap(inputFromClient);
                dataQueueOpsimToSimona.queueData(new ExtInputDataContainer(0L, dataForSimona));
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }

            // --------------------------------------------------------------------------------------------------

            // Trigger OpSimSimulator to provide result edu.ie3.simopsim.data

            try {
                logger.info("Wait for results from SIMONA!");
                // Wait for results from SIMONA!
                ExtResultContainer results = dataQueueSimonaToOpsim.takeData();
                logger.info("Received results from SIMONA!");

                logger.debug("Send Aggregated SetPoints for " + this.cli.getCurrentSimulationTime().toString());
                List<OpSimAggregatedSetPoints> osmAggSetPoints = SimopsimUtils.createSimopsimOutputList(
                        writable,
                        cli.getClock().getActualTime().plus(delta).getMillis(),
                        results
                );

                System.out.println();
                System.out.println("--- Produced OpSim Messages --------------------------------------------");
                osmAggSetPoints.forEach(
                        msg -> SimopsimUtils.printMessage(msg, this.cli.getCurrentSimulationTime())
                );
                System.out.println("------------------------------------------------------------------------");
                System.out.println();
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

    protected  <T extends OpSimMessage> void sendToOpSim(
            List<T> inputFromComponent
    ) {
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
