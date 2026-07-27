package org.ociarmmonitor.controller;

import org.ociarmmonitor.auth.AuthService;
import org.ociarmmonitor.auth.AuthSession;
import org.ociarmmonitor.auth.ChangePasswordRequest;
import org.ociarmmonitor.auth.LoginRequest;
import org.ociarmmonitor.common.ApiResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

  private final AuthService authService;

  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  @PostMapping("/login")
  public ApiResponse<AuthSession> login(@Valid @RequestBody LoginRequest request, HttpSession session) {
    return ApiResponse.ok(authService.login(request, session), "登录成功");
  }

  @GetMapping("/me")
  public ApiResponse<AuthSession> current(HttpSession session) {
    return ApiResponse.ok(authService.current(session));
  }

  @PostMapping("/password")
  public ApiResponse<AuthSession> changePassword(@Valid @RequestBody ChangePasswordRequest request, HttpSession session) {
    return ApiResponse.ok(authService.changePassword(request, session), "密码已更新");
  }

  @PostMapping("/logout")
  public ApiResponse<Void> logout(HttpSession session) {
    authService.logout(session);
    return ApiResponse.ok(null, "已退出登录");
  }
}
