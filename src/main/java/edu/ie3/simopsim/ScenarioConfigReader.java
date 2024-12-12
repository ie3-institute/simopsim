/*
 * © 2024. TU Dortmund University,
 * Institute of Energy Systems, Energy Efficiency and Energy Economics,
 * Research group Distribution grid planning and operation
 */

package edu.ie3.simopsim;

import com.sun.xml.bind.v2.ContextFactory;
import de.fhg.iwes.opsim.datamodel.generated.scenarioconfig.ScenarioConfig;
import java.io.StringReader;
import java.util.Collections;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.stream.StreamSource;

public final class ScenarioConfigReader {

  private ScenarioConfigReader() {}

  public static ScenarioConfig read(String componentConfig) throws JAXBException {
    JAXBContext context =
        ContextFactory.createContext(new Class[] {ScenarioConfig.class}, Collections.emptyMap());
    Unmarshaller unmarshaller = context.createUnmarshaller();
    JAXBElement<ScenarioConfig> root =
        unmarshaller.unmarshal(
            new StreamSource(new StringReader(componentConfig)), ScenarioConfig.class);
    return root.getValue();
  }
}
