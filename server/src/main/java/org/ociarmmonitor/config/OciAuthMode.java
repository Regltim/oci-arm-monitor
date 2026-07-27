package org.ociarmmonitor.config;

import java.util.Locale;

public enum OciAuthMode {
  CONFIG_FILE("config_file", "API Key / OCI config"),
  INSTANCE_PRINCIPAL("instance_principal", "Instance Principal");

  private final String value;
  private final String label;

  OciAuthMode(String value, String label) {
    this.value = value;
    this.label = label;
  }

  public String value() {
    return value;
  }

  public String label() {
    return label;
  }

  public static OciAuthMode from(String value) {
    if (value == null || value.isBlank()) {
      return CONFIG_FILE;
    }

    String normalizedValue = value.trim()
      .replace('-', '_')
      .toLowerCase(Locale.ROOT);

    return switch (normalizedValue) {
      case "instance_principal", "instance_principals" -> INSTANCE_PRINCIPAL;
      case "config_file", "config", "api_key", "apikey" -> CONFIG_FILE;
      default -> throw new IllegalArgumentException("不支持的 OCI_AUTH_MODE：" + value);
    };
  }
}
