package org.ociarmmonitor.instance;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CloudInstanceRepository {

  private final JdbcTemplate jdbcTemplate;

  public CloudInstanceRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public long count() {
    Long count = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM cloud_instance", Long.class);
    return count == null ? 0 : count;
  }

  public List<CloudInstance> findAll() {
    return jdbcTemplate.query("""
      SELECT id, display_name, region, compartment_id, shape, lifecycle_state, ocpus, memory_gb,
        boot_volume_gb, public_ip, private_ip, created_at, updated_at
      FROM cloud_instance
      ORDER BY display_name ASC
      """, (resultSet, rowNum) -> mapInstance(resultSet));
  }

  public void deleteByIds(List<String> ids) {
    if (ids.isEmpty()) {
      jdbcTemplate.update("DELETE FROM cloud_instance");
      return;
    }
    String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
    jdbcTemplate.update("DELETE FROM cloud_instance WHERE id NOT IN (" + placeholders + ")", ids.toArray());
  }

  public double sumOcpus() {
    Double value = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(ocpus), 0) FROM cloud_instance", Double.class);
    return value == null ? 0 : value;
  }

  public double sumMemoryGb() {
    Double value = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(memory_gb), 0) FROM cloud_instance", Double.class);
    return value == null ? 0 : value;
  }

  public double sumBootVolumeGb() {
    Double value = jdbcTemplate.queryForObject("SELECT COALESCE(SUM(boot_volume_gb), 0) FROM cloud_instance", Double.class);
    return value == null ? 0 : value;
  }

  public void save(CloudInstance instance) {
    jdbcTemplate.update("""
      INSERT INTO cloud_instance (
        id, display_name, region, compartment_id, shape, lifecycle_state, ocpus, memory_gb,
        boot_volume_gb, public_ip, private_ip, created_at, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(id) DO UPDATE SET
        display_name = excluded.display_name,
        region = excluded.region,
        compartment_id = excluded.compartment_id,
        shape = excluded.shape,
        lifecycle_state = excluded.lifecycle_state,
        ocpus = excluded.ocpus,
        memory_gb = excluded.memory_gb,
        boot_volume_gb = excluded.boot_volume_gb,
        public_ip = excluded.public_ip,
        private_ip = excluded.private_ip,
        updated_at = excluded.updated_at
      """,
      instance.id(),
      instance.displayName(),
      instance.region(),
      instance.compartmentId(),
      instance.shape(),
      instance.lifecycleState(),
      instance.ocpus(),
      instance.memoryGb(),
      instance.bootVolumeGb(),
      instance.publicIp(),
      instance.privateIp(),
      instance.createdAt(),
      instance.updatedAt()
    );
  }

  private CloudInstance mapInstance(ResultSet resultSet) throws SQLException {
    return new CloudInstance(
      resultSet.getString("id"),
      resultSet.getString("display_name"),
      resultSet.getString("region"),
      resultSet.getString("compartment_id"),
      resultSet.getString("shape"),
      resultSet.getString("lifecycle_state"),
      resultSet.getDouble("ocpus"),
      resultSet.getDouble("memory_gb"),
      resultSet.getDouble("boot_volume_gb"),
      resultSet.getString("public_ip"),
      resultSet.getString("private_ip"),
      resultSet.getString("created_at"),
      resultSet.getString("updated_at")
    );
  }
}
