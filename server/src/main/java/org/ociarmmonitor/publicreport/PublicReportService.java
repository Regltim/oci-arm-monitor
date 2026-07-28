package org.ociarmmonitor.publicreport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.net.URI;
import java.net.URISyntaxException;
import org.ociarmmonitor.notification.DailyReportData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicReportService {

  public static final int MIN_TTL_DAYS = 1;
  public static final int MAX_TTL_DAYS = 90;

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;
  private final PublicReportSnapshotMapper snapshotMapper;
  private final Clock clock;
  private final SecureRandom secureRandom;

  @Autowired
  public PublicReportService(
    JdbcTemplate jdbcTemplate,
    ObjectMapper objectMapper,
    PublicReportSnapshotMapper snapshotMapper
  ) {
    this(jdbcTemplate, objectMapper, snapshotMapper, Clock.systemUTC(), new SecureRandom());
  }

  PublicReportService(
    JdbcTemplate jdbcTemplate,
    ObjectMapper objectMapper,
    PublicReportSnapshotMapper snapshotMapper,
    Clock clock,
    SecureRandom secureRandom
  ) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
    this.snapshotMapper = snapshotMapper;
    this.clock = clock;
    this.secureRandom = secureRandom;
  }

  @Transactional
  public PublicReportSnapshot createSnapshot(DailyReportData data, int ttlDays) {
    validateTtlDays(ttlDays);
    Instant createdAt = clock.instant();
    Instant expiresAt = createdAt.plus(ttlDays, ChronoUnit.DAYS);
    String snapshotId = UUID.randomUUID().toString();
    String payloadJson = serialize(snapshotMapper.map(data));
    jdbcTemplate.update(
      """
      INSERT INTO public_report_snapshot(id, report_type, payload_json, created_at, expires_at)
      VALUES (?, 'DAILY', ?, ?, ?)
      """,
      snapshotId,
      payloadJson,
      createdAt.toString(),
      expiresAt.toString()
    );
    return new PublicReportSnapshot(snapshotId, createdAt.toString(), expiresAt.toString());
  }

  @Transactional
  public PublicReportAccess issueAccess(PublicReportSnapshot snapshot, String publicUrl) {
    String normalizedPublicUrl = normalizePublicUrl(publicUrl);
    String token = generateToken();
    String accessId = UUID.randomUUID().toString();
    jdbcTemplate.update(
      """
      INSERT INTO public_report_access(
        id, snapshot_id, token_hash, expires_at, revoked_at, last_accessed_at, access_count
      ) VALUES (?, ?, ?, ?, NULL, NULL, 0)
      """,
      accessId,
      snapshot.id(),
      hashToken(token),
      snapshot.expiresAt()
    );
    String url = normalizedPublicUrl + "/#/r/" + snapshot.id() + "?token=" + token;
    return new PublicReportAccess(snapshot.id(), token, url, snapshot.expiresAt());
  }

  @Transactional
  public Optional<PublicReportView> find(String snapshotId, String token) {
    if (snapshotId == null || snapshotId.isBlank() || token == null || token.isBlank()) {
      return Optional.empty();
    }
    String now = clock.instant().toString();
    byte[] suppliedHash = digest(token);
    List<AccessRow> accessRows = jdbcTemplate.query(
      """
      SELECT id, token_hash
      FROM public_report_access
      WHERE snapshot_id = ? AND revoked_at IS NULL AND expires_at > ?
      """,
      (resultSet, rowNum) -> new AccessRow(
        resultSet.getString("id"),
        resultSet.getString("token_hash")
      ),
      snapshotId,
      now
    );
    AccessRow matchedAccess = accessRows.stream()
      .filter(row -> MessageDigest.isEqual(decodeHash(row.tokenHash()), suppliedHash))
      .findFirst()
      .orElse(null);
    if (matchedAccess == null) {
      return Optional.empty();
    }

    List<PublicReportView> reports = jdbcTemplate.query(
      """
      SELECT id, payload_json, created_at, expires_at
      FROM public_report_snapshot
      WHERE id = ? AND expires_at > ?
      """,
      (resultSet, rowNum) -> new PublicReportView(
        resultSet.getString("id"),
        resultSet.getString("created_at"),
        resultSet.getString("expires_at"),
        deserialize(resultSet.getString("payload_json"))
      ),
      snapshotId,
      now
    );
    if (reports.isEmpty()) {
      return Optional.empty();
    }
    jdbcTemplate.update(
      """
      UPDATE public_report_access
      SET last_accessed_at = ?, access_count = access_count + 1
      WHERE id = ?
      """,
      now,
      matchedAccess.id()
    );
    return Optional.of(reports.get(0));
  }

  @Transactional
  public int cleanupExpired() {
    String now = clock.instant().toString();
    int deletedAccess = jdbcTemplate.update(
      "DELETE FROM public_report_access WHERE expires_at <= ? OR snapshot_id IN (SELECT id FROM public_report_snapshot WHERE expires_at <= ?)",
      now,
      now
    );
    int deletedSnapshots = jdbcTemplate.update(
      "DELETE FROM public_report_snapshot WHERE expires_at <= ?",
      now
    );
    return deletedAccess + deletedSnapshots;
  }

  String hashToken(String token) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest(token));
  }

  private String generateToken() {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private byte[] digest(String token) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("当前 Java 运行环境不支持 SHA-256", exception);
    }
  }

  private byte[] decodeHash(String tokenHash) {
    try {
      return Base64.getUrlDecoder().decode(tokenHash);
    } catch (IllegalArgumentException exception) {
      return new byte[32];
    }
  }

  private String serialize(PublicReportPayload payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("公开日报快照生成失败", exception);
    }
  }

  private PublicReportPayload deserialize(String payloadJson) {
    try {
      return objectMapper.readValue(payloadJson, PublicReportPayload.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("公开日报快照读取失败", exception);
    }
  }

  private String normalizePublicUrl(String publicUrl) {
    String normalized = publicUrl == null ? "" : publicUrl.trim();
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    try {
      URI uri = new URI(normalized);
      String path = uri.getRawPath();
      if (
        !"https".equalsIgnoreCase(uri.getScheme())
          || uri.getHost() == null
          || uri.getHost().isBlank()
          || uri.getUserInfo() != null
          || !(path == null || path.isBlank())
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null
      ) {
        throw new IllegalArgumentException("免登录明细需要配置有效的 HTTPS MONITOR_PUBLIC_URL");
      }
    } catch (URISyntaxException exception) {
      throw new IllegalArgumentException("免登录明细需要配置有效的 HTTPS MONITOR_PUBLIC_URL");
    }
    return normalized;
  }

  public static void validateTtlDays(int ttlDays) {
    if (ttlDays < MIN_TTL_DAYS || ttlDays > MAX_TTL_DAYS) {
      throw new IllegalArgumentException("明细访问令牌有效期必须为 1 至 90 天");
    }
  }

  private record AccessRow(String id, String tokenHash) {
  }
}
