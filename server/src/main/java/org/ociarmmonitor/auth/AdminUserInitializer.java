package org.ociarmmonitor.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class AdminUserInitializer implements CommandLineRunner {

  private final String adminUsername;
  private final String adminPassword;
  private final AdminUserRepository adminUserRepository;
  private final PasswordHasher passwordHasher;

  public AdminUserInitializer(
    @Value("${monitor.admin.username:}") String adminUsername,
    @Value("${monitor.admin.password:}") String adminPassword,
    AdminUserRepository adminUserRepository,
    PasswordHasher passwordHasher
  ) {
    this.adminUsername = adminUsername;
    this.adminPassword = adminPassword;
    this.adminUserRepository = adminUserRepository;
    this.passwordHasher = passwordHasher;
  }

  @Override
  public void run(String... args) {
    if (adminUserRepository.count() > 0 || adminUsername.isBlank() || adminPassword.isBlank()) {
      return;
    }
    String salt = passwordHasher.generateSalt();
    adminUserRepository.create(adminUsername, passwordHasher.hash(adminPassword, salt), salt);
  }
}
