package org.ociarmmonitor.config;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FreeQuotaRepository {

  private static final String QUOTA_ID = "default";
  private final JdbcTemplate jdbcTemplate;

  public FreeQuotaRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public FreeQuota getQuota() {
    List<FreeQuota> quotas = jdbcTemplate.query("""
      SELECT ampere_ocpu_hours, ampere_memory_gb_hours, block_volume_gb, outbound_data_transfer_gb,
        monitoring_ingestion_points, monitoring_retrieval_points, updated_at
      FROM free_quota
      WHERE id = ?
      """, (resultSet, rowNum) -> mapQuota(resultSet), QUOTA_ID);
    if (quotas.isEmpty()) {
      FreeQuota defaultQuota = new FreeQuota(1500, 9000, 200, 10240, 500000000, 1000000000, Instant.now().toString());
      save(defaultQuota);
      return defaultQuota;
    }
    return quotas.get(0);
  }

  public void save(FreeQuota freeQuota) {
    jdbcTemplate.update("""
      INSERT INTO free_quota(
        id, ampere_ocpu_hours, ampere_memory_gb_hours, block_volume_gb, outbound_data_transfer_gb,
        monitoring_ingestion_points, monitoring_retrieval_points, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(id) DO UPDATE SET
        ampere_ocpu_hours = excluded.ampere_ocpu_hours,
        ampere_memory_gb_hours = excluded.ampere_memory_gb_hours,
        block_volume_gb = excluded.block_volume_gb,
        outbound_data_transfer_gb = excluded.outbound_data_transfer_gb,
        monitoring_ingestion_points = excluded.monitoring_ingestion_points,
        monitoring_retrieval_points = excluded.monitoring_retrieval_points,
        updated_at = excluded.updated_at
      """,
      QUOTA_ID,
      freeQuota.ampereOcpuHours(),
      freeQuota.ampereMemoryGbHours(),
      freeQuota.blockVolumeGb(),
      freeQuota.outboundDataTransferGb(),
      freeQuota.monitoringIngestionPoints(),
      freeQuota.monitoringRetrievalPoints(),
      freeQuota.updatedAt()
    );
  }

  private FreeQuota mapQuota(ResultSet resultSet) throws SQLException {
    return new FreeQuota(
      resultSet.getDouble("ampere_ocpu_hours"),
      resultSet.getDouble("ampere_memory_gb_hours"),
      resultSet.getDouble("block_volume_gb"),
      resultSet.getDouble("outbound_data_transfer_gb"),
      resultSet.getDouble("monitoring_ingestion_points"),
      resultSet.getDouble("monitoring_retrieval_points"),
      resultSet.getString("updated_at")
    );
  }
}
