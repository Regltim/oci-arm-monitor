package org.ociarmmonitor.auth;

public record AdminUser(
  String id,
  String username,
  String passwordHash,
  String passwordSalt,
  String createdAt,
  String updatedAt
) {
}
