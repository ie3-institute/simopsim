/*
 * © 2024. TU Dortmund University,
 * Institute of Energy Systems, Energy Efficiency and Energy Economics,
 * Research group Distribution grid planning and operation
 */

package edu.ie3.simopsim.config;

import com.typesafe.config.ConfigFactory;
import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Simple parser for the cli arguments. */
public class ArgsParser {

  /**
   * Parsed arguments.
   *
   * @param mainArgs provided arguments
   * @param urlToOpsim the url to opsim
   * @param mappingPath of the ext mapping source
   */
  public record Arguments(
      String[] mainArgs, Optional<String> urlToOpsim, Optional<Path> mappingPath) {}

  /**
   * Method for parsing the provided arguments.
   *
   * @param args arguments the main simulation is started with
   * @return the parsed arguments
   */
  public static Arguments parse(String[] args) {
    Map<String, String> parsedArgs = new HashMap<>();

    for (String arg : args) {
      String[] key_value = arg.split("=");
      parsedArgs.put(key_value[0], key_value[1]);
    }

    String value = parsedArgs.get("--config");

    if (value == null || value.isEmpty()) {
      throw new RuntimeException("No value found for required element --config!");
    }

    SimopsimConfig config = SimopsimConfig.from(ConfigFactory.parseFile(new File(value)));

    Optional<String> urlToOpsim = config.urlToOpsim();
    Optional<Path> mappingPath = config.mappingPath();

    return new Arguments(args, urlToOpsim, mappingPath);
  }
}
