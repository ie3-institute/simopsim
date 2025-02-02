package edu.ie3.simopsim;

import de.fhg.iwes.opsim.datamodel.generated.asset.Asset;
import de.fhg.iwes.opsim.datamodel.generated.flexforecast.OpSimFlexibilityForecastMessage;
import de.fhg.iwes.opsim.datamodel.generated.realtimedata.*;
import edu.ie3.datamodel.models.StandardUnits;
import edu.ie3.datamodel.models.value.PValue;
import edu.ie3.datamodel.models.value.Value;
import edu.ie3.simona.api.data.em.NoSetPointValue;
import edu.ie3.simona.api.data.results.ExtResultContainer;
import java.io.IOException;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.TimeoutException;
import org.joda.time.DateTime;
import tech.units.indriya.quantity.Quantities;
import org.apache.logging.log4j.Logger;

/** Helpful methods to implement a SIMONA-OPSIM coupling. */
public class SimopsimUtils {

  private SimopsimUtils() {}

  public static void runSimopsim(SimonaProxy simonaProxy, String urlToOpsim) {
    try {
      simonaProxy.getCli().addProxy(simonaProxy);
      simonaProxy.getCli().reconnect(urlToOpsim);
    } catch (URISyntaxException
        | IOException
        | NoSuchAlgorithmException
        | KeyManagementException
        | TimeoutException e) {
      throw new RuntimeException(e);
    }
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
      ExtResultContainer results, Asset asset, Long delta) {
    List<OpSimSetPoint> osmSetPoints = new ArrayList<>(Collections.emptyList());
    for (MeasurementValueType valueType : asset.getMeasurableQuantities()) {
      if (valueType.equals(MeasurementValueType.ACTIVE_POWER)) {
        osmSetPoints.add(
            new OpSimSetPoint(
                results.getActivePower(asset.getGridAssetId()),
                SetPointValueType.fromValue(valueType.value())));
      }
      if (valueType.equals(MeasurementValueType.REACTIVE_POWER)) {
        osmSetPoints.add(
            new OpSimSetPoint(
                results.getReactivePower(asset.getGridAssetId()),
                SetPointValueType.fromValue(valueType.value())));
      }
    }
    return new OpSimAggregatedSetPoints(asset.getGridAssetId(), delta, osmSetPoints);
  }

  public static Map<String, Value> createInputMap(
          Queue<OpSimMessage> inputFromClient,
          Logger logger
  ) {
      Map<String, Value> dataForSimona = new HashMap<>();
      inputFromClient.forEach(
            osm -> {
                if (osm instanceof OpSimScheduleMessage ossm) {
                    logger.info("OpSimScheduleMessage: " + ossm);
                    double switchPosition = 0.0;
                    double activePower = 0.0;
                    for (OpSimScheduleElement ose : ossm.getScheduleElements()) {
                        if (ose.getScheduledValueType() == SetPointValueType.SWITCH_POSITION) {
                            switchPosition = ose.getScheduledValue();
                        } else if (ose.getScheduledValueType() == SetPointValueType.ACTIVE_POWER) {
                            activePower = ose.getScheduledValue();
                        }
                    }
                    PValue opsimValue;
                    if (Double.isNaN(switchPosition) || Double.isNaN(activePower)) {
                        logger.info("[{}] Received invalid data because of communication problems!", ossm.getAssetId());
                        opsimValue = new NoSetPointValue(Quantities.getQuantity(0.0, StandardUnits.ACTIVE_POWER_IN));
                    } else {
                        if (switchPosition == 1.0) {
                            logger.info("[{}] Control Signal from Netzbetrieb because of congestions!", ossm.getAssetId());
                            opsimValue = new PValue(Quantities.getQuantity(activePower, StandardUnits.ACTIVE_POWER_IN));
                        } else { //No Control Signal from Netzbetrieb
                            opsimValue = new NoSetPointValue(Quantities.getQuantity(activePower, StandardUnits.ACTIVE_POWER_IN));
                        }
                    }
                    dataForSimona.put(
                            ossm.getAssetId(),
                            opsimValue
                    );
                }
            });

    return new HashMap<>(dataForSimona);
  }

  public static List<OpSimAggregatedSetPoints> createSimopsimOutputList(
      Set<Asset> writable, Long delta, ExtResultContainer simonaResults) {
    List<OpSimAggregatedSetPoints> osmAggSetPoints = new ArrayList<>(Collections.emptyList());
    writable.forEach(
        asset -> osmAggSetPoints.add(createAggregatedSetPoints(simonaResults, asset, delta)));
    return osmAggSetPoints;
  }
}
