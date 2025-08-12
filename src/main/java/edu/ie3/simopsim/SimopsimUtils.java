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
import edu.ie3.datamodel.models.result.ResultEntity;
import edu.ie3.datamodel.models.result.system.SystemParticipantResult;
import edu.ie3.datamodel.models.value.PValue;
import edu.ie3.simona.api.data.container.ExtResultContainer;
import edu.ie3.simona.api.data.model.em.EmSetPoint;
import edu.ie3.simona.api.mapping.ExtEntityMapping;
import edu.ie3.util.quantities.PowerSystemUnits;
import java.io.IOException;
import java.util.*;
import org.apache.logging.slf4j.SLF4JLogger;
import org.joda.time.DateTime;
import org.slf4j.LoggerFactory;
import tech.units.indriya.quantity.Quantities;

/** Helpful methods to implement a SIMONA-OPSIM coupling. */
public class SimopsimUtils {

  private SimopsimUtils() {}

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

    if (osm instanceof OpSimAggregatedMeasurements osmms) {
      osmms
          .getOpSimMeasurements()
          .forEach(
              osmm ->
                  strb.append(osmm.getMeasurementType())
                      .append(";")
                      .append(osmm.getMeasurementValue())
                      .append(";"));

    } else if (osm instanceof OpSimAggregatedSetPoints ossp) {
      ossp.getOpSimSetPoints()
          .forEach(
              osmm ->
                  strb.append(osmm.getSetPointValueType())
                      .append(";")
                      .append(osmm.getSetPointValue())
                      .append(";"));
    } else if (osm instanceof OpSimFlexibilityForecastMessage off) {
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
    } else if (osm instanceof OpSimScheduleMessage osme) {
      osme.getScheduleElements()
          .forEach(
              ose ->
                  strb.append(ose.getScheduleTimeInUTC())
                      .append(";")
                      .append(ose.getScheduledValueType())
                      .append(";")
                      .append(ose.getScheduledValue())
                      .append(";"));
    }

    System.out.println(strb);
  }

  public static OpSimAggregatedSetPoints createAggregatedSetPoints(
      ExtResultContainer container, Asset asset, Long delta, ExtEntityMapping mapping) {
    List<OpSimSetPoint> osmSetPoints = new ArrayList<>(Collections.emptyList());

    Map<String, UUID> idToUuid = mapping.getExtId2UuidMapping();

    String gridId = asset.getGridAssetId();
    UUID id = idToUuid.get(gridId);

    ResultEntity result = container.getResult(id);

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
      Set<Asset> writable, Long delta, ExtResultContainer container, ExtEntityMapping mapping) {
    List<OpSimAggregatedSetPoints> osmAggSetPoints = new ArrayList<>(Collections.emptyList());
    writable.forEach(
        asset -> osmAggSetPoints.add(createAggregatedSetPoints(container, asset, delta, mapping)));
    return osmAggSetPoints;
  }
}
