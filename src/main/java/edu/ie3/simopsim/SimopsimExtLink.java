/*
 * © 2024. TU Dortmund University,
 * Institute of Energy Systems, Energy Efficiency and Energy Economics,
 * Research group Distribution grid planning and operation
 */

package edu.ie3.simopsim;

import de.fhg.iee.opsim.client.Client;
import edu.ie3.datamodel.models.input.container.SystemParticipants;
import edu.ie3.datamodel.models.input.system.SystemParticipantInput;
import edu.ie3.simona.api.ExtLinkInterface;
import edu.ie3.simona.api.data.SetupData;
import edu.ie3.simona.api.exceptions.NoExtSimulationException;
import edu.ie3.simona.api.mapping.ExtEntityMapping;
import edu.ie3.simona.api.simulation.ExtSimulation;
import java.io.IOException;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
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
  public void setup(SetupData data) {
    ArgsParser.Arguments arguments = ArgsParser.parse(data.config());

    Optional<String> urlToOpsim = arguments.urlToOpsim();

    SystemParticipants systemParticipants = data.gridContainer().getSystemParticipants();

    List<SystemParticipantInput> participants = systemParticipants.allEntitiesAsList();

    Map<UUID, UUID> participantToNode = new HashMap<>();
    Map<UUID, List<UUID>> nodeToParticipants = new HashMap<>();

    participants.forEach(
        participant -> {
          UUID uuid = participant.getUuid();
          UUID nodeInput = participant.getNode().getUuid();
          participantToNode.put(uuid, nodeInput);
          nodeToParticipants.computeIfAbsent(nodeInput, n -> new ArrayList<>()).add(uuid);
        });

    ExtEntityMapping mapping = SimopsimUtils.buildMapping(participants);

    if (urlToOpsim.isPresent()) {
      SimonaProxy proxy = new SimonaProxy(mapping, nodeToParticipants, participantToNode);

      new Thread("helper") {
        @Override
        public void run() {
          try {
            Client client = SimopsimUtils.clientWithProxy(proxy);
            client.start(urlToOpsim.get());
          } catch (IOException
              | URISyntaxException
              | NoSuchAlgorithmException
              | KeyManagementException
              | TimeoutException e) {
            throw new RuntimeException(e);
          }
          log.info("Connected to: {}", urlToOpsim.get());
        }
      }.start();

      emSimulation = new OpsimSimulation("SIMONA Simulation", proxy, mapping);
      emSimulation.setSetupData(data);
    }
  }
}
