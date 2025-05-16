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
import java.nio.file.Path;
import java.util.Optional;

public class SimopsimExtLink implements ExtLinkInterface {

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
    Optional<Path> mappingPath = arguments.mappingPath();

    if (urlToOpsim.isPresent() && mappingPath.isPresent()) {
      emSimulation = new OpsimSimulation("SIMONA Simulation", urlToOpsim.get(), mappingPath.get());
      emSimulation.setAdapterData(data);
    }
  }
}
