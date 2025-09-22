/*
 * © 2024. TU Dortmund University,
 * Institute of Energy Systems, Energy Efficiency and Energy Economics,
 * Research group Distribution grid planning and operation
 */

package edu.ie3.simopsim;

import de.fhg.iee.opsim.client.Client;
import de.fhg.iwes.opsim.datamodel.generated.asset.Asset;
import de.fhg.iwes.opsim.datamodel.generated.flexforecast.OpSimFlexibilityForecastMessage;
import de.fhg.iwes.opsim.datamodel.generated.realtimedata.*;
import edu.ie3.datamodel.models.StandardUnits;
import edu.ie3.datamodel.models.input.AssetInput;
import edu.ie3.datamodel.models.input.EmInput;
import edu.ie3.datamodel.models.input.NodeInput;
import edu.ie3.datamodel.models.input.container.JointGridContainer;
import edu.ie3.datamodel.models.input.system.SystemParticipantInput;
import edu.ie3.datamodel.models.result.ResultEntity;
import edu.ie3.datamodel.models.result.system.SystemParticipantResult;
import edu.ie3.datamodel.models.value.PValue;
import edu.ie3.simona.api.data.container.ExtOutputContainer;
import edu.ie3.simona.api.data.model.em.EmSetPoint;
import edu.ie3.simona.api.mapping.DataType;
import edu.ie3.simona.api.mapping.ExtEntityEntry;
import edu.ie3.simona.api.mapping.ExtEntityMapping;
import edu.ie3.util.quantities.PowerSystemUnits;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import org.apache.logging.slf4j.SLF4JLogger;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.units.indriya.quantity.Quantities;

/** Helpful methods to implement a SIMONA-OPSIM coupling. */
public class SimopsimUtils {

  private static final Logger log = LoggerFactory.getLogger(SimopsimUtils.class);

  private SimopsimUtils() {}

  public static ExtEntityMapping buildMapping(JointGridContainer container) {
    Map<EmInput, Set<NodeInput>> entities = new HashMap<>();

    List<SystemParticipantInput> participants =
        container.getSystemParticipants().allEntitiesAsList();

    participants.forEach(
        participant ->
            participant
                .getControllingEm()
                .ifPresent(
                    em -> {
                      entities.putIfAbsent(em, new HashSet<>());
                      entities.get(em).add(participant.getNode());
                    }));

    List<ExtEntityEntry> entries = new ArrayList<>();

    entities.forEach(
        (em, nodes) -> {
          switch (nodes.size()) {
            case 0 ->
                throw new RuntimeException("No nodes found for EmInput '" + em.getId() + "'.");
            case 1 -> {
              String id = nodes.iterator().next().getId();
              entries.add(new ExtEntityEntry(em.getUuid(), id, DataType.EM));
            }
            default ->
                throw new RuntimeException(
                    "Too many nodes found for EmInput '" + em.getId() + "'.");
          }
        });

    Consumer<AssetInput> consumer =
        asset -> entries.add(new ExtEntityEntry(asset.getUuid(), asset.getId(), DataType.RESULT));

    participants.forEach(consumer);

    return new ExtEntityMapping(entries);
  }

  public static Client clientWithProxy(SimonaProxy proxy) throws IOException {
    SLF4JLogger logger = new SLF4JLogger("Client", LoggerFactory.getLogger(Client.class));
    Client client = new Client(logger);
    proxy.SetUp("SIMONA", client, logger);
    client.addProxy(proxy);

    return client;
  }

  public static void printMessage(OpSimMessage osm, DateTime simulationTime) {
    StringBuilder strb = new StringBuilder();
    String topic = osm.getAssetId();
    DateTime dt = new DateTime(osm.getDelta());
    strb.append(simulationTime.toString())
        .append("|")
        .append(dt)
        .append(" ")
        .append(topic)
        .append(";");

    switch (osm) {
      case OpSimAggregatedMeasurements osmms ->
          osmms
              .getOpSimMeasurements()
              .forEach(
                  osmm ->
                      strb.append(osmm.getMeasurementType())
                          .append(";")
                          .append(osmm.getMeasurementValue())
                          .append(";"));
      case OpSimAggregatedSetPoints ossp ->
          ossp.getOpSimSetPoints()
              .forEach(
                  osmm ->
                      strb.append(osmm.getSetPointValueType())
                          .append(";")
                          .append(osmm.getSetPointValue())
                          .append(";"));
      case OpSimFlexibilityForecastMessage off ->
          off.getForecastMessages()
              .forEach(
                  ofe ->
                      strb.append(ofe.getLeadTimeInUTC())
                          .append(";")
                          .append(ofe.getType())
                          .append(";")
                          .append(ofe.getMax())
                          .append(";")
                          .append(ofe.getMin())
                          .append(";"));
      case OpSimScheduleMessage osme ->
          osme.getScheduleElements()
              .forEach(
                  ose ->
                      strb.append(ose.getScheduleTimeInUTC())
                          .append(";")
                          .append(ose.getScheduledValueType())
                          .append(";")
                          .append(ose.getScheduledValue())
                          .append(";"));
      default -> {}
    }

    System.out.println(strb);
  }

  public static OpSimAggregatedSetPoints createAggregatedSetPoints(
      ExtOutputContainer container, Asset asset, Long delta, ExtEntityMapping mapping) {
    List<OpSimSetPoint> osmSetPoints = new ArrayList<>(Collections.emptyList());

    Map<String, UUID> idToUuid = mapping.getExtId2UuidMapping();

    String gridId = asset.getGridAssetId();
    UUID id = idToUuid.get(gridId);

    List<ResultEntity> results = container.getResult(id);

    if (results.isEmpty()) {
      log.warn("No results received for asset '{}' in tick {}", id, container.getTick());
    }

    for (ResultEntity result : results) {
      for (MeasurementValueType valueType : asset.getMeasurableQuantities()) {
        if (result instanceof SystemParticipantResult res) {
          if (valueType.equals(MeasurementValueType.ACTIVE_POWER)) {
            osmSetPoints.add(
                new OpSimSetPoint(
                    res.getP().to(PowerSystemUnits.MEGAWATT).getValue().doubleValue(),
                    SetPointValueType.fromValue(valueType.value())));
          }
          if (valueType.equals(MeasurementValueType.REACTIVE_POWER)) {
            osmSetPoints.add(
                new OpSimSetPoint(
                    res.getQ().to(PowerSystemUnits.MEGAVAR).getValue().doubleValue(),
                    SetPointValueType.fromValue(valueType.value())));
          }
        } else {
          throw new RuntimeException("Expected system participant result!");
        }
      }
    }
    return new OpSimAggregatedSetPoints(asset.getGridAssetId(), delta, osmSetPoints);
  }

  public static List<EmSetPoint> createEmSetPoints(
      Queue<OpSimMessage> inputFromClient, ExtEntityMapping mapping) {
    List<EmSetPoint> dataForSimona = new ArrayList<>();
    Map<String, UUID> idToUuid = mapping.getExtId2UuidMapping();

    inputFromClient.forEach(
        osm -> {
          if (osm instanceof OpSimScheduleMessage ossm) {
            for (OpSimScheduleElement ose : ossm.getScheduleElements()) {
              if (ose.getScheduledValueType() == SetPointValueType.ACTIVE_POWER) {

                dataForSimona.add(
                    new EmSetPoint(
                        idToUuid.get(ossm.getAssetId()),
                        idToUuid.get(ossm.getAssetId()),
                        new PValue(
                            Quantities.getQuantity(
                                ose.getScheduledValue(), StandardUnits.ACTIVE_POWER_IN))));
              }
            }
          }
        });

    return dataForSimona;
  }

  public static List<OpSimAggregatedSetPoints> createSimopsimOutputList(
      Set<Asset> writable, Long delta, ExtOutputContainer container, ExtEntityMapping mapping) {
    List<OpSimAggregatedSetPoints> osmAggSetPoints = new ArrayList<>(Collections.emptyList());
    writable.forEach(
        asset -> osmAggSetPoints.add(createAggregatedSetPoints(container, asset, delta, mapping)));
    return osmAggSetPoints;
  }
}
