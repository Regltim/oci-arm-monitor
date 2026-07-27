package org.ociarmmonitor.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpSession;

class AuthServiceTest {

  private AdminUserRepository adminUserRepository;
  private AuthService authService;
  private PasswordHasher passwordHasher;

  @BeforeEach
  void setUp() {
    SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
    adminUserRepository = new AdminUserRepository(new JdbcTemplate(dataSource));
    passwordHasher = new PasswordHasher();
    authService = new AuthService(adminUserRepository, passwordHasher);
  }

  @Test
  void changePasswordUpdatesPasswordHashAndKeepsCurrentSession() {
    createAdminUser("old-password-123");
    MockHttpSession session = loggedInSession();

    AuthSession authSession = authService.changePassword(
      new ChangePasswordRequest("old-password-123", "new-password-456", "new-password-456"),
      session
    );

    AdminUser updatedUser = adminUserRepository.findByUsername("admin").orElseThrow();
    assertThat(authSession.username()).isEqualTo("admin");
    assertThat(passwordHasher.matches("new-password-456", updatedUser)).isTrue();
    assertThat(passwordHasher.matches("old-password-123", updatedUser)).isFalse();
    assertThat(session.getAttribute(AuthService.SESSION_USER_KEY)).isEqualTo("admin");
  }

  @Test
  void changePasswordRejectsWrongCurrentPassword() {
    AdminUser adminUser = createAdminUser("old-password-123");

    assertThatThrownBy(() -> authService.changePassword(
      new ChangePasswordRequest("wrong-password", "new-password-456", "new-password-456"),
      loggedInSession()
    )).isInstanceOf(IllegalArgumentException.class)
      .hasMessage("当前密码不正确");

    AdminUser unchangedUser = adminUserRepository.findByUsername("admin").orElseThrow();
    assertThat(unchangedUser.passwordHash()).isEqualTo(adminUser.passwordHash());
  }

  @Test
  void changePasswordRejectsMismatchedConfirmation() {
    AdminUser adminUser = createAdminUser("old-password-123");

    assertThatThrownBy(() -> authService.changePassword(
      new ChangePasswordRequest("old-password-123", "new-password-456", "another-password-789"),
      loggedInSession()
    )).isInstanceOf(IllegalArgumentException.class)
      .hasMessage("两次输入的新密码不一致");

    AdminUser unchangedUser = adminUserRepository.findByUsername("admin").orElseThrow();
    assertThat(unchangedUser.passwordHash()).isEqualTo(adminUser.passwordHash());
  }

  @Test
  void changePasswordRejectsCurrentPasswordAsNewPassword() {
    AdminUser adminUser = createAdminUser("old-password-123");

    assertThatThrownBy(() -> authService.changePassword(
      new ChangePasswordRequest("old-password-123", "old-password-123", "old-password-123"),
      loggedInSession()
    )).isInstanceOf(IllegalArgumentException.class)
      .hasMessage("新密码不能与当前密码一致");

    AdminUser unchangedUser = adminUserRepository.findByUsername("admin").orElseThrow();
    assertThat(unchangedUser.passwordHash()).isEqualTo(adminUser.passwordHash());
  }

  @Test
  void changePasswordRequiresCurrentSession() {
    assertThatThrownBy(() -> authService.changePassword(
      new ChangePasswordRequest("old-password-123", "new-password-456", "new-password-456"),
      new MockHttpSession()
    )).isInstanceOf(IllegalArgumentException.class)
      .hasMessage("未登录或登录已过期");
  }

  private AdminUser createAdminUser(String password) {
    String salt = passwordHasher.generateSalt();
    return adminUserRepository.create("admin", passwordHasher.hash(password, salt), salt);
  }

  private MockHttpSession loggedInSession() {
    MockHttpSession session = new MockHttpSession();
    session.setAttribute(AuthService.SESSION_USER_KEY, "admin");
    return session;
  }
}
