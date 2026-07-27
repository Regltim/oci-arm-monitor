package org.ociarmmonitor.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
  @NotBlank(message = "当前密码不能为空") String currentPassword,
  @NotBlank(message = "新密码不能为空")
  @Size(min = 8, max = 128, message = "新密码长度需在 8 到 128 位之间")
  String newPassword,
  @NotBlank(message = "确认密码不能为空") String confirmPassword
) {
}
