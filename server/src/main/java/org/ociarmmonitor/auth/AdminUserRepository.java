package org.ociarmmonitor.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminUserRepository {

  private final JdbcTemplate jdbcTemplate;

  public AdminUserRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public long count() {
    Long count = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM admin_user", Long.class);
    return count == null ? 0 : count;
  }

  public Optional<AdminUser> findByUsername(String username) {
    List<AdminUser> users = jdbcTemplate.query("""
      SELECT id, username, password_hash, password_salt, created_at, updated_at
      FROM admin_user
      WHERE username = ?
      """, (resultSet, rowNum) -> mapUser(resultSet), username);
    return users.stream().findFirst();
  }

  public AdminUser create(String username, String passwordHash, String passwordSalt) {
    String now = Instant.now().toString();
    AdminUser adminUser = new AdminUser(UUID.randomUUID().toString(), username, passwordHash, passwordSalt, now, now);
    jdbcTemplate.update("""
      INSERT INTO admin_user(id, username, password_hash, password_salt, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?)
      """,
      adminUser.id(),
      adminUser.username(),
      adminUser.passwordHash(),
      adminUser.passwordSalt(),
      adminUser.createdAt(),
      adminUser.updatedAt()
    );
    return adminUser;
  }

  public void updatePassword(String id, String passwordHash, String passwordSalt) {
    int updatedRows = jdbcTemplate.update("""
      UPDATE admin_user
      SET password_hash = ?, password_salt = ?, updated_at = ?
      WHERE id = ?
      """, passwordHash, passwordSalt, Instant.now().toString(), id);
    if (updatedRows != 1) {
      throw new IllegalArgumentException("当前账号不存在，请重新登录");
    }
  }

  private AdminUser mapUser(ResultSet resultSet) throws SQLException {
    return new AdminUser(
      resultSet.getString("id"),
      resultSet.getString("username"),
      resultSet.getString("password_hash"),
      resultSet.getString("password_salt"),
      resultSet.getString("created_at"),
      resultSet.getString("updated_at")
    );
  }
}
