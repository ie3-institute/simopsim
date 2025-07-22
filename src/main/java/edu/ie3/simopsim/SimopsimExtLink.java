/*
 * © 2024. TU Dortmund University,
 * Institute of Energy Systems, Energy Efficiency and Energy Economics,
 * Research group Distribution grid planning and operation
 */

package edu.ie3.simopsim;

import edu.ie3.simona.api.ExtLinkInterface;
import edu.ie3.simona.api.exceptions.NoExtSimulationException;
import edu.ie3.simona.api.simulation.ExtSimAdapterData;
import edu.ie3.simona.api.simulation.ExtSimulation;
import edu.ie3.simopsim.config.ArgsParser;
import edu.ie3.simopsim.initialization.InitializationQueue;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimopsimExtLink implements ExtLinkInterface {

  public static Logger log = LoggerFactory.getLogger(SimopsimExtLink.class);

  OpsimSimulation emSimulation;

  @Override
  public ExtSimulation getExtSimulation() {
    if (emSimulation == null) {
      throw new NoExtSimulationException(SimopsimExtLink.class);
    }

    return emSimulation;
  }

  @Override
  public void setup(ExtSimAdapterData data) {
    ArgsParser.Arguments arguments = ArgsParser.parse(data.getMainArgs());

    Optional<String> urlToOpsim = arguments.urlToOpsim();

    if (urlToOpsim.isPresent()) {
      InitializationQueue queue = new InitializationQueue();

      SimonaProxy simonaProxy = new SimonaProxy(queue);

      emSimulation = new OpsimSimulation("SIMONA Simulation", queue);
      emSimulation.setAdapterData(data);

      SimopsimUtils.runSimopsim(simonaProxy, urlToOpsim.get());
      log.info("Connected to: {}", urlToOpsim);
    }
  }
}
