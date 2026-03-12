/*
 * © 2025. TU Dortmund University,
 * Institute of Energy Systems, Energy Efficiency and Energy Economics,
 * Research group Distribution grid planning and operation
 */

package edu.ie3.simopsim;

import edu.ie3.simona.api.simulation.ExtCoSimFramework;

public interface InitializationData extends ExtCoSimFramework.InitData {

  record SimulatorData(long stepSize) implements InitializationData {}
}
