/*
 * © 2024. TU Dortmund University,
 * Institute of Energy Systems, Energy Efficiency and Energy Economics,
 * Research group Distribution grid planning and operation
 */

package edu.ie3.simopsim.em;

import edu.ie3.datamodel.models.value.Value;
import edu.ie3.simona.api.data.ExtDataConnection;
import edu.ie3.simona.api.data.em.ExtEmDataConnection;
import edu.ie3.simopsim.OpsimSimulation;
import edu.ie3.simopsim.SimopsimUtils;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class OpsimEmSimulation extends OpsimSimulation {

  private final ExtEmDataConnection extEmDataConnection;

  public OpsimEmSimulation(String urlToOpsim, Path mappingPath) {
    super("OpsimEmSimulation", mappingPath);

    this.extEmDataConnection = buildEmConnection(mapping, log);

    SimopsimUtils.runSimopsim(simonaProxy, urlToOpsim);
    log.info("Connected to: {}", urlToOpsim);
  }

  @Override
  protected void sendToSimona(
      long tick, Map<String, Value> inputMap, Optional<Long> maybeNextTick) {
    sendEmDataToSimona(extEmDataConnection, tick, inputMap, maybeNextTick, log);
  }

  @Override
  public Set<ExtDataConnection> getDataConnections() {
    return Set.of(extEmDataConnection, extResultDataConnection);
  }
}
