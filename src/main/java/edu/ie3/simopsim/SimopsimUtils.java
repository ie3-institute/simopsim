package edu.ie3.simopsim;

import de.fhg.iwes.opsim.datamodel.generated.asset.Asset;
import de.fhg.iwes.opsim.datamodel.generated.flexforecast.OpSimFlexibilityElement;
import de.fhg.iwes.opsim.datamodel.generated.flexforecast.OpSimFlexibilityForecastMessage;
import de.fhg.iwes.opsim.datamodel.generated.realtimedata.*;
import edu.ie3.datamodel.models.StandardUnits;
import edu.ie3.datamodel.models.value.PValue;
import edu.ie3.datamodel.models.value.Value;
import edu.ie3.simona.api.data.results.ExtResultContainer;
import org.joda.time.DateTime;
import tech.units.indriya.quantity.Quantities;

import java.io.IOException;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.TimeoutException;

/**
 * Helpful methods to implement a SIMONA-OPSIM coupling.
 */
public class SimopsimUtils {

    private SimopsimUtils() {}

    public static void runSimopsim(SimonaProxy simonaProxy, String urlToOpsim) {
        try {
            simonaProxy.getCli().addProxy(simonaProxy);
            simonaProxy.getCli().reconnect(urlToOpsim);
        } catch (URISyntaxException | IOException | NoSuchAlgorithmException | KeyManagementException |
                 TimeoutException e) {
            throw new RuntimeException(e);
        }
    }

    public static void printMessage(OpSimMessage osm, DateTime simulationTime) {
        StringBuilder strb = new StringBuilder();
        String topic = osm.getAssetId();
        DateTime dt = new DateTime(osm.getDelta());
        strb.append(simulationTime.toString());
        strb.append("|");
        strb.append(dt.toString());
        strb.append(" ");
        strb.append(topic);
        strb.append(";");
        Iterator var6;
        if (osm instanceof OpSimAggregatedMeasurements) {
            OpSimAggregatedMeasurements osmms = (OpSimAggregatedMeasurements)osm;
            var6 = osmms.getOpSimMeasurements().iterator();

            while(var6.hasNext()) {
                OpSimMeasurement osmm = (OpSimMeasurement)var6.next();
                strb.append(osmm.getMeasurementType());
                strb.append(";");
                strb.append(osmm.getMeasurementValue());
                strb.append(";");
            }
        } else if (osm instanceof OpSimAggregatedSetPoints) {
            OpSimAggregatedSetPoints ossp = (OpSimAggregatedSetPoints)osm;
            var6 = ossp.getOpSimSetPoints().iterator();

            while(var6.hasNext()) {
                OpSimSetPoint osp = (OpSimSetPoint)var6.next();
                strb.append(osp.getSetPointValueType());
                strb.append(";");
                strb.append(osp.getSetPointValue());
                strb.append(";");
            }
        } else if (osm instanceof OpSimFlexibilityForecastMessage) {
            OpSimFlexibilityForecastMessage off = (OpSimFlexibilityForecastMessage)osm;
            var6 = off.getForecastMessages().iterator();

            while(var6.hasNext()) {
                OpSimFlexibilityElement ofe = (OpSimFlexibilityElement)var6.next();
                strb.append(ofe.getLeadTimeInUTC());
                strb.append(";");
                strb.append(ofe.getType());
                strb.append(";");
                strb.append(ofe.getMax());
                strb.append(";");
                strb.append(ofe.getMin());
                strb.append(";");
            }
        } else if (osm instanceof OpSimScheduleMessage) {
            OpSimScheduleMessage osme = (OpSimScheduleMessage)osm;
            var6 = osme.getScheduleElements().iterator();

            while(var6.hasNext()) {
                OpSimScheduleElement ose = (OpSimScheduleElement)var6.next();
                strb.append(ose.getScheduleTimeInUTC());
                strb.append(";");
                strb.append(ose.getScheduledValueType());
                strb.append(";");
                strb.append(ose.getScheduledValue());
                strb.append(";");
            }
        }

        System.out.println(strb.toString());
    }

    public static OpSimAggregatedSetPoints createAggregatedSetPoints(
            ExtResultContainer results,
            Asset asset,
            Long delta
    ) {
        List<OpSimSetPoint> osmSetPoints = new ArrayList<>(Collections.emptyList());
        for (MeasurementValueType valueType : asset.getMeasurableQuantities()) {
            if (valueType.equals(MeasurementValueType.ACTIVE_POWER)) {
                osmSetPoints.add(
                        new OpSimSetPoint(
                                results.getActivePower(asset.getGridAssetId()),
                                SetPointValueType.fromValue(valueType.value())
                        )
                );
            }
            if (valueType.equals(MeasurementValueType.REACTIVE_POWER)) {
                osmSetPoints.add(
                        new OpSimSetPoint(
                                results.getReactivePower(asset.getGridAssetId()),
                                SetPointValueType.fromValue(valueType.value())
                        )
                );
            }
        }
        return new OpSimAggregatedSetPoints(
                asset.getGridAssetId(),
                delta,
                osmSetPoints
        );
    }

    public static Map<String, Value> createInputMap(Queue<OpSimMessage> inputFromClient) {
        Iterator<OpSimMessage> iteratorInput = inputFromClient.iterator();
        Map<String, Value> dataForSimona = new HashMap<>();
        while(iteratorInput.hasNext()) {
            OpSimMessage osm = iteratorInput.next();
            if (osm instanceof OpSimScheduleMessage ossm) {
                for (OpSimScheduleElement ose : ossm.getScheduleElements()) {
                    if (ose.getScheduledValueType() == SetPointValueType.ACTIVE_POWER) {
                        dataForSimona.put(ossm.getAssetId(), new PValue(Quantities.getQuantity(ose.getScheduledValue(), StandardUnits.ACTIVE_POWER_IN)));
                    }
                }
            }
        }

        return new HashMap<>(dataForSimona);
    }

    public static List<OpSimAggregatedSetPoints> createSimopsimOutputList(
            Set<Asset> writable,
            Long delta,
            ExtResultContainer simonaResults
    ) {
        List<OpSimAggregatedSetPoints> osmAggSetPoints = new ArrayList<>(Collections.emptyList());
        writable.forEach(
                asset -> {
                    osmAggSetPoints.add(
                            createAggregatedSetPoints(
                                    simonaResults,
                                    asset,
                                    delta
                            )
                    );
                }
        );
        return osmAggSetPoints;
    }
}
