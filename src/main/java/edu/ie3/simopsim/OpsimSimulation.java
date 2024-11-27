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

public abstract class OpsimSimulation extends ExtCoSimulation {

    protected final SimonaProxy simonaProxy;

    protected final ExtEntityMapping mapping;

    protected final ExtResultDataConnection extResultDataConnection;

    protected OpsimSimulation(String simulationName, Path mappingPath) {
        this(simulationName, "SimonaProxy", mappingPath);
    }

    protected OpsimSimulation(String simulationName, String extSimulatorName, Path mappingPath) {
        super(simulationName, extSimulatorName);

        try {
            this.mapping = ExtEntityMappingSource.fromFile(mappingPath);
        } catch (SourceException e) {
            throw new RuntimeException(e);
        }

        this.simonaProxy = new SimonaProxy();
        simonaProxy.setConnectionToSimonaApi(
                dataQueueExtCoSimulatorToSimonaApi,
                dataQueueSimonaApiToExtCoSimulator
        );

        this.extResultDataConnection = buildResultConnection(mapping);
    }

    @Override
    protected Long initialize() {
        log.info("+++++++++++++++++++++++++++ initialization of the external simulation +++++++++++++++++++++++++++");
        return 0L;
    }

    @Override
    protected Optional<Long> doActivity(long tick) {
        log.info("+++++ External simulation triggered for tick {} +++++", tick);

        Optional<Long> nextTick = Optional.of(tick + deltaT);

        try {
            ExtInputDataContainer rawEmData = simonaProxy.dataQueueOpsimToSimona.takeData();
            Map<String, Value> inputMap = rawEmData.getSimonaInputMap();

            sendToSimona(tick, inputMap, nextTick);

            sendDataToExt(extResultDataConnection, tick, nextTick);

            log.info("***** External simulation for tick {} completed. Next simulation tick = {} *****", tick, nextTick);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return nextTick;
    }

    protected abstract void sendToSimona(long tick, Map<String, Value> inputMap, Optional<Long> maybeNextTick);
}
