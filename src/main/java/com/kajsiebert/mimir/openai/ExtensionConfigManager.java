package com.kajsiebert.mimir.openai;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

/** Loads and provides extension-to-configuration mappings from a YAML file. */
public class ExtensionConfigManager {
  private static final Logger LOG = LoggerFactory.getLogger(ExtensionConfigManager.class);
  private final Map<String, ExtensionConfig> configMap;

  private ExtensionConfigManager(Map<String, ExtensionConfig> configMap) {
    this.configMap = configMap;
  }

  /**
   * Loads extension configuration from the given YAML file path. If the file is not found on the
   * filesystem, attempts to load it from the classpath.
   */
  @SuppressWarnings("unchecked")
  public static ExtensionConfigManager load(String path) throws IOException {
    Yaml yaml = new Yaml();
    InputStream in;
    File file = new File(path);
    if (file.exists()) {
      in = new FileInputStream(file);
    } else {
      in = ExtensionConfigManager.class.getResourceAsStream("/" + path);
      if (in == null) {
        throw new FileNotFoundException("Extension config file not found: " + path);
      }
    }
    Map<String, Object> root = yaml.load(in);
    in.close();
    Map<Integer, Object> extensions = (Map<Integer, Object>) root.get("extensions");
    Map<String, ExtensionConfig> map = new HashMap<>();
    if (extensions != null) {
      for (Map.Entry<Integer, Object> entry : extensions.entrySet()) {
        Integer ext = entry.getKey();
        String configFile = (String) entry.getValue();
        Map<String, Object> scientistConfig = loadScientistConfig(configFile);
        String name = (String) scientistConfig.get("name");
        String voice = (String) scientistConfig.get("voice");
        String greeting = (String) scientistConfig.get("initialisation");
        String instructions = (String) scientistConfig.get("instructions");
        map.put(String.valueOf(ext), new ExtensionConfig(instructions, voice, greeting));
      }
    }
    return new ExtensionConfigManager(map);
  }

  /** Loads a scientist's configuration from their individual YAML file. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> loadScientistConfig(String configFile) throws IOException {
    Yaml yaml = new Yaml();
    InputStream in;
    File file = new File(configFile);
    if (file.exists()) {
      in = new FileInputStream(file);
    } else {
      in = ExtensionConfigManager.class.getResourceAsStream("/" + configFile);
      if (in == null) {
        throw new FileNotFoundException("Scientist config file not found: " + configFile);
      }
    }
    Map<String, Object> config = yaml.load(in);
    in.close();
    return config;
  }

  /** Retrieves the configuration for a given extension, or null if none exists. */
  public ExtensionConfig getConfig(String extension) {
    ExtensionConfig cfg = configMap.get(extension);
    if (cfg == null) {
      LOG.error("No config found for extension: {}", extension);
      cfg = getDefaultConfig();
    }
    return cfg;
  }

  /** Returns a default configuration if one exists. */
  public ExtensionConfig getDefaultConfig() {
    if (configMap.isEmpty()) {
      return null;
    }
    return configMap.values().iterator().next();
  }
}
