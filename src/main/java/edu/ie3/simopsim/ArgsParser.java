/*
 * © 2024. TU Dortmund University,
 * Institute of Energy Systems, Energy Efficiency and Energy Economics,
 * Research group Distribution grid planning and operation
 */

package edu.ie3.simopsim;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import edu.ie3.datamodel.utils.Try;
import java.nio.file.Path;
import java.util.Optional;

/** Simple parser for the cli arguments. */
public class ArgsParser {

  /**
   * Parsed arguments.
   *
   * @param urlToOpsim the url to opsim
   * @param mappingPath of the ext mapping source
   */
  public record Arguments(Optional<String> urlToOpsim, Optional<Path> mappingPath) {}

  /**
   * Method for parsing the provided arguments.
   *
   * @param config the config provided by SIMONA
   * @return the parsed arguments
   */
  public static Arguments parse(Config config) {
    Config simopsimConfig = config.getConfig("simopsim");

    Optional<String> urlToOpsim = extractFrom(simopsimConfig, "urlToOpsim");
    Optional<Path> mappingPath = extractFrom(simopsimConfig, "mappingPath").map(Path::of);

    return new Arguments(urlToOpsim, mappingPath);
  }

  public static Optional<String> extractFrom(Config simopsimConfig, String path) {
    return Try.of(() -> simopsimConfig.getString(path), ConfigException.class).getData();
  }
}
