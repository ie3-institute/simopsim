/*
 * © 2024. TU Dortmund University,
 * Institute of Energy Systems, Energy Efficiency and Energy Economics,
 * Research group Distribution grid planning and operation
 */

package edu.ie3.simopsim.config;

public class SimopsimConfig {
  public final SimopsimConfig.Simopsim simopsim;

  public static class Simopsim {
    public final java.lang.String mappingPath;
    public final java.lang.String urlToOpsim;

    public Simopsim(
        com.typesafe.config.Config c,
        java.lang.String parentPath,
        $TsCfgValidator $tsCfgValidator) {
      this.mappingPath = $_reqStr(parentPath, c, "mappingPath", $tsCfgValidator);
      this.urlToOpsim = $_reqStr(parentPath, c, "urlToOpsim", $tsCfgValidator);
    }

    private static java.lang.String $_reqStr(
        java.lang.String parentPath,
        com.typesafe.config.Config c,
        java.lang.String path,
        $TsCfgValidator $tsCfgValidator) {
      if (c == null) return null;
      try {
        return c.getString(path);
      } catch (com.typesafe.config.ConfigException e) {
        $tsCfgValidator.addBadPath(parentPath + path, e);
        return null;
      }
    }
  }

  public SimopsimConfig(com.typesafe.config.Config c) {
    final $TsCfgValidator $tsCfgValidator = new $TsCfgValidator();
    final java.lang.String parentPath = "";
    this.simopsim =
        c.hasPathOrNull("simopsim")
            ? new SimopsimConfig.Simopsim(
                c.getConfig("simopsim"), parentPath + "simopsim.", $tsCfgValidator)
            : null;
    $tsCfgValidator.validate();
  }

  private static final class $TsCfgValidator {
    private final java.util.List<java.lang.String> badPaths = new java.util.ArrayList<>();

    void addBadPath(java.lang.String path, com.typesafe.config.ConfigException e) {
      badPaths.add("'" + path + "': " + e.getClass().getName() + "(" + e.getMessage() + ")");
    }

    void validate() {
      if (!badPaths.isEmpty()) {
        java.lang.StringBuilder sb = new java.lang.StringBuilder("Invalid configuration:");
        for (java.lang.String path : badPaths) {
          sb.append("\n    ").append(path);
        }
        throw new com.typesafe.config.ConfigException(sb.toString()) {};
      }
    }
  }
}
