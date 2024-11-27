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
  public record Arguments(String[] mainArgs, Optional<String> urlToOpsim, Optional<Path> mappingPath) {}

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

    SimopsimConfig config =
        new SimopsimConfig(ConfigFactory.parseFile(new File(extract(parsedArgs, "--config"))));

    Optional<String> urlToOpsim = Optional.ofNullable(config.simopsim.urlToOpsim);
    Optional<Path> mappingPath = Optional.ofNullable(config.simopsim.mappingPath).map(Path::of);


    return new Arguments(args, urlToOpsim, mappingPath);
  }

  /**
   * Method for extracting values.
   *
   * @param parsedArgs map: argument key to value
   * @param element that should be extracted
   * @return a string value
   */
  private static String extract(Map<String, String> parsedArgs, String element) {
    String value = parsedArgs.get(element);

    if (value == null || value.isEmpty()) {
      throw new RuntimeException("No value found for required element " + element + "!");
    }

    return value;
  }
}
