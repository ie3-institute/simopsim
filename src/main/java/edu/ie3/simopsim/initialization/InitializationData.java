/*
 * © 2025. TU Dortmund University,
 * Institute of Energy Systems, Energy Efficiency and Energy Economics,
 * Research group Distribution grid planning and operation
 */

package edu.ie3.simopsim.initialization;

import edu.ie3.simona.api.data.ExtDataContainerQueue;
import edu.ie3.simona.api.data.container.ExtInputContainer;
import edu.ie3.simona.api.data.container.ExtResultContainer;
import edu.ie3.simona.api.mapping.ExtEntityMapping;
import java.util.function.BiConsumer;

public interface InitializationData {

  record SimulatorData(
      long stepSize,
      ExtEntityMapping mapping,
      BiConsumer<
              ExtDataContainerQueue<ExtInputContainer>, ExtDataContainerQueue<ExtResultContainer>>
          setConnectionToSimonaApi)
      implements InitializationData {}
}
