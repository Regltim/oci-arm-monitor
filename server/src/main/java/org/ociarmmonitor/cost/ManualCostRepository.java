package org.ociarmmonitor.cost;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ManualCostRepository {

  private final JdbcTemplate jdbcTemplate;

  public ManualCostRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public ManualCost create(ManualCostCreateRequest request) {
    ManualCost manualCost = new ManualCost(
      UUID.randomUUID().toString(),
      request.costName(),
      request.category(),
      request.amount(),
      request.currency(),
      request.occurredOn(),
      request.note(),
      Instant.now().toString()
    );
    jdbcTemplate.update("""
      INSERT INTO manual_cost(id, cost_name, category, amount, currency, occurred_on, note, created_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      """,
      manualCost.id(),
      manualCost.costName(),
      manualCost.category(),
      manualCost.amount(),
      manualCost.currency(),
      manualCost.occurredOn(),
      manualCost.note(),
      manualCost.createdAt()
    );
    return manualCost;
  }

  public List<ManualCost> listCurrentMonth() {
    return listMonth(YearMonth.now());
  }

  public List<ManualCost> listMonth(YearMonth month) {
    String monthPrefix = month.toString();
    return jdbcTemplate.query("""
      SELECT id, cost_name, category, amount, currency, occurred_on, note, created_at
      FROM manual_cost
      WHERE occurred_on LIKE ?
      ORDER BY occurred_on DESC, created_at DESC
      """, (resultSet, rowNum) -> mapManualCost(resultSet), monthPrefix + "%");
  }

  public double costForCurrentMonth() {
    return costForMonth(YearMonth.now());
  }

  public double costForMonth(YearMonth month) {
    String monthPrefix = month.toString();
    Double value = jdbcTemplate.queryForObject("""
      SELECT COALESCE(SUM(amount), 0)
      FROM manual_cost
      WHERE occurred_on LIKE ?
      """, Double.class, monthPrefix + "%");
    return value == null ? 0 : value;
  }

  public void delete(String id) {
    jdbcTemplate.update("DELETE FROM manual_cost WHERE id = ?", id);
  }

  private ManualCost mapManualCost(ResultSet resultSet) throws SQLException {
    return new ManualCost(
      resultSet.getString("id"),
      resultSet.getString("cost_name"),
      resultSet.getString("category"),
      resultSet.getDouble("amount"),
      resultSet.getString("currency"),
      resultSet.getString("occurred_on"),
      resultSet.getString("note"),
      resultSet.getString("created_at")
    );
  }
}
