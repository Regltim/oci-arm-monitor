package org.ociarmmonitor.auth;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

  public static final String SESSION_USER_KEY = "MONITOR_AUTH_USER";

  private final AdminUserRepository adminUserRepository;
  private final PasswordHasher passwordHasher;

  public AuthService(AdminUserRepository adminUserRepository, PasswordHasher passwordHasher) {
    this.adminUserRepository = adminUserRepository;
    this.passwordHasher = passwordHasher;
  }

  public AuthSession login(LoginRequest request, HttpSession session) {
    AdminUser adminUser = adminUserRepository.findByUsername(request.username())
      .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));
    if (!passwordHasher.matches(request.password(), adminUser)) {
      throw new IllegalArgumentException("用户名或密码错误");
    }
    session.setAttribute(SESSION_USER_KEY, adminUser.username());
    return new AuthSession(adminUser.username());
  }

  public AuthSession current(HttpSession session) {
    Object username = session.getAttribute(SESSION_USER_KEY);
    if (username instanceof String usernameText && !usernameText.isBlank()) {
      return new AuthSession(usernameText);
    }
    throw new IllegalArgumentException("未登录或登录已过期");
  }

  public AuthSession changePassword(ChangePasswordRequest request, HttpSession session) {
    AuthSession authSession = current(session);
    AdminUser adminUser = adminUserRepository.findByUsername(authSession.username())
      .orElseThrow(() -> new IllegalArgumentException("当前账号不存在，请重新登录"));
    if (!passwordHasher.matches(request.currentPassword(), adminUser)) {
      throw new IllegalArgumentException("当前密码不正确");
    }
    if (!request.newPassword().equals(request.confirmPassword())) {
      throw new IllegalArgumentException("两次输入的新密码不一致");
    }
    if (passwordHasher.matches(request.newPassword(), adminUser)) {
      throw new IllegalArgumentException("新密码不能与当前密码一致");
    }

    String salt = passwordHasher.generateSalt();
    adminUserRepository.updatePassword(adminUser.id(), passwordHasher.hash(request.newPassword(), salt), salt);
    return authSession;
  }

  public void logout(HttpSession session) {
    session.invalidate();
  }
}
