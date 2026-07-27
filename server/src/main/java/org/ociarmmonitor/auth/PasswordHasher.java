package org.ociarmmonitor.auth;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Component;

@Component
public class PasswordHasher {

  private static final int ITERATIONS = 120_000;
  private static final int KEY_LENGTH = 256;
  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  public String generateSalt() {
    byte[] salt = new byte[16];
    SECURE_RANDOM.nextBytes(salt);
    return Base64.getEncoder().encodeToString(salt);
  }

  public String hash(String password, String salt) {
    try {
      PBEKeySpec keySpec = new PBEKeySpec(
        password.toCharArray(),
        Base64.getDecoder().decode(salt),
        ITERATIONS,
        KEY_LENGTH
      );
      SecretKeyFactory secretKeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
      return Base64.getEncoder().encodeToString(secretKeyFactory.generateSecret(keySpec).getEncoded());
    } catch (NoSuchAlgorithmException | InvalidKeySpecException exception) {
      throw new IllegalStateException("密码哈希失败", exception);
    }
  }

  public boolean matches(String rawPassword, AdminUser adminUser) {
    return hash(rawPassword, adminUser.passwordSalt()).equals(adminUser.passwordHash());
  }
}
