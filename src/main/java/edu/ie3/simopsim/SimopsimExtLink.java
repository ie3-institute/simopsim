/*
 * © 2024. TU Dortmund University,
 * Institute of Energy Systems, Energy Efficiency and Energy Economics,
 * Research group Distribution grid planning and operation
 */

package edu.ie3.simopsim;

import de.fhg.iee.opsim.client.Client;
import edu.ie3.simona.api.ExtLinkInterface;
import edu.ie3.simona.api.exceptions.NoExtSimulationException;
import edu.ie3.simona.api.mapping.ExtEntityMapping;
import edu.ie3.simona.api.simulation.ExtSimAdapterData;
import edu.ie3.simona.api.simulation.ExtSimulation;
import edu.ie3.simopsim.config.ArgsParser;
import edu.ie3.simopsim.initialization.InitializationQueue;
import java.io.IOException;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
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

    ExtEntityMapping mapping = SimopsimUtils.buildMapping(data.getGrid());

    if (urlToOpsim.isPresent()) {
      InitializationQueue queue = new InitializationQueue();

      try {
        SimonaProxy proxy = new SimonaProxy(queue, mapping);
        Client client = SimopsimUtils.clientWithProxy(proxy);
        client.start(urlToOpsim.get());
        log.info("Connected to: {}", urlToOpsim);
      } catch (IOException
          | URISyntaxException
          | NoSuchAlgorithmException
          | KeyManagementException
          | TimeoutException e) {
        throw new RuntimeException(e);
      }

      emSimulation = new OpsimSimulation("SIMONA Simulation", queue, mapping);
      emSimulation.setAdapterData(data);
    }
  }
}
