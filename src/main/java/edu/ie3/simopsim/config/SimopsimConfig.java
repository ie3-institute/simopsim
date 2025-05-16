/*
 * © 2024. TU Dortmund University,
 * Institute of Energy Systems, Energy Efficiency and Energy Economics,
 * Research group Distribution grid planning and operation
 */

package edu.ie3.simopsim.config;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;
import edu.ie3.datamodel.utils.Try;
import java.nio.file.Path;
import java.util.Optional;

public record SimopsimConfig(Optional<Path> mappingPath, Optional<String> urlToOpsim) {

  public static SimopsimConfig from(Config typeSafeConfig) {
    Config subConfig = typeSafeConfig.getConfig("simopsim");

    Optional<Path> mappingPath =
        Try.of(() -> subConfig.getString("mappingPath"), ConfigException.class)
            .getData()
            .map(Path::of);

    Optional<String> urlToOpsim =
        Try.of(() -> subConfig.getString("urlToOpsim"), ConfigException.class).getData();

    return new SimopsimConfig(mappingPath, urlToOpsim);
  }
}
