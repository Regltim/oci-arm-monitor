package org.ociarmmonitor.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class WechatSecretCipherTest {

  private static final String VALID_KEY = Base64.getEncoder().encodeToString(
    "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)
  );

  @Test
  void encryptsAndDecryptsSecretWithoutStoringPlaintext() {
    WechatSecretCipher cipher = new WechatSecretCipher(VALID_KEY);

    String encrypted = cipher.encrypt("wx_example_secret");

    assertThat(encrypted).startsWith("v1:").doesNotContain("wx_example_secret");
    assertThat(cipher.decrypt(encrypted)).isEqualTo("wx_example_secret");
  }

  @Test
  void usesANewInitializationVectorForEveryEncryption() {
    WechatSecretCipher cipher = new WechatSecretCipher(VALID_KEY);

    String first = cipher.encrypt("same-value");
    String second = cipher.encrypt("same-value");

    assertThat(first).isNotEqualTo(second);
    assertThat(cipher.decrypt(first)).isEqualTo("same-value");
    assertThat(cipher.decrypt(second)).isEqualTo("same-value");
  }

  @Test
  void rejectsInvalidConfiguredKey() {
    assertThatThrownBy(() -> new WechatSecretCipher("not-a-base64-key"))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("MONITOR_SETTINGS_ENCRYPTION_KEY");
  }

  @Test
  void rejectsTamperedCiphertext() {
    WechatSecretCipher cipher = new WechatSecretCipher(VALID_KEY);
    String encrypted = cipher.encrypt("wx_example_secret");
    String tampered = encrypted.substring(0, encrypted.length() - 2) + "AA";

    assertThatThrownBy(() -> cipher.decrypt(tampered))
      .isInstanceOf(IllegalArgumentException.class)
      .hasMessage("通知配置无法解密，请检查 MONITOR_SETTINGS_ENCRYPTION_KEY");
  }
}
