package org.ociarmmonitor.notification;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WechatSecretCipher {

  private static final String VERSION_PREFIX = "v1:";
  private static final int KEY_BYTES = 32;
  private static final int IV_BYTES = 12;
  private static final int GCM_TAG_BITS = 128;

  private final SecureRandom secureRandom = new SecureRandom();
  private final SecretKey secretKey;

  public WechatSecretCipher(@Value("${monitor.wechat.settings-encryption-key:}") String encodedKey) {
    this.secretKey = parseKey(encodedKey);
  }

  public boolean isReady() {
    return secretKey != null;
  }

  public String encrypt(String plaintext) {
    if (plaintext == null || plaintext.isBlank()) {
      return "";
    }
    requireReady();
    try {
      byte[] initializationVector = new byte[IV_BYTES];
      secureRandom.nextBytes(initializationVector);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, initializationVector));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      byte[] payload = ByteBuffer.allocate(initializationVector.length + ciphertext.length)
        .put(initializationVector)
        .put(ciphertext)
        .array();
      return VERSION_PREFIX + Base64.getEncoder().encodeToString(payload);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("通知配置加密失败", exception);
    }
  }

  public String decrypt(String encryptedValue) {
    if (encryptedValue == null || encryptedValue.isBlank()) {
      return "";
    }
    requireReady();
    try {
      if (!encryptedValue.startsWith(VERSION_PREFIX)) {
        throw new IllegalArgumentException("Unsupported encrypted value version");
      }
      byte[] payload = Base64.getDecoder().decode(encryptedValue.substring(VERSION_PREFIX.length()));
      if (payload.length <= IV_BYTES) {
        throw new IllegalArgumentException("Encrypted value is incomplete");
      }
      byte[] initializationVector = new byte[IV_BYTES];
      byte[] ciphertext = new byte[payload.length - IV_BYTES];
      System.arraycopy(payload, 0, initializationVector, 0, IV_BYTES);
      System.arraycopy(payload, IV_BYTES, ciphertext, 0, ciphertext.length);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, initializationVector));
      return new String(cipher.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8);
    } catch (GeneralSecurityException | IllegalArgumentException exception) {
      throw new IllegalArgumentException("通知配置无法解密，请检查 MONITOR_SETTINGS_ENCRYPTION_KEY", exception);
    }
  }

  private SecretKey parseKey(String encodedKey) {
    if (encodedKey == null || encodedKey.isBlank()) {
      return null;
    }
    try {
      byte[] keyBytes = Base64.getDecoder().decode(encodedKey.trim());
      if (keyBytes.length != KEY_BYTES) {
        throw new IllegalArgumentException("MONITOR_SETTINGS_ENCRYPTION_KEY 必须是 Base64 编码的 32 字节密钥");
      }
      return new SecretKeySpec(keyBytes, "AES");
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("MONITOR_SETTINGS_ENCRYPTION_KEY 必须是 Base64 编码的 32 字节密钥", exception);
    }
  }

  private void requireReady() {
    if (!isReady()) {
      throw new IllegalArgumentException("未配置 MONITOR_SETTINGS_ENCRYPTION_KEY，无法保存公众号凭据");
    }
  }
}
