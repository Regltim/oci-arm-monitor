package org.ociarmmonitor.cost;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CostRepository {

  private final JdbcTemplate jdbcTemplate;

  public CostRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void save(CostDaily costDaily) {
    jdbcTemplate.update("""
      INSERT INTO cost_daily(service_name, resource_id, stat_date, usage_amount, usage_unit, cost_amount, currency)
      VALUES (?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(service_name, resource_id, stat_date, usage_unit) DO UPDATE SET
        usage_amount = excluded.usage_amount,
        cost_amount = excluded.cost_amount,
        currency = excluded.currency
      """,
      costDaily.serviceName(),
      normalizeResourceId(costDaily.resourceId()),
      costDaily.statDate(),
      costDaily.usageAmount(),
      costDaily.usageUnit(),
      costDaily.costAmount(),
      costDaily.currency()
    );
  }

  public void deleteCurrentMonth() {
    String monthPrefix = currentMonthPrefix();
    jdbcTemplate.update("DELETE FROM cost_daily WHERE stat_date LIKE ?", monthPrefix + "%");
  }

  public List<CostDaily> listCurrentMonth() {
    return listMonth(YearMonth.now());
  }

  public List<CostDaily> listMonth(YearMonth month) {
    String monthPrefix = month.toString();
    return jdbcTemplate.query("""
      SELECT service_name, resource_id, stat_date, usage_amount, usage_unit, cost_amount, currency
      FROM cost_daily
      WHERE stat_date LIKE ?
      ORDER BY stat_date ASC
      """, (resultSet, rowNum) -> mapCost(resultSet), monthPrefix + "%");
  }

  public double costForCurrentMonth() {
    return costForMonth(YearMonth.now());
  }

  public double costForMonth(YearMonth month) {
    String monthPrefix = month.toString();
    Double value = jdbcTemplate.queryForObject("""
      SELECT COALESCE(SUM(cost_amount), 0)
      FROM cost_daily
      WHERE stat_date LIKE ?
      """, Double.class, monthPrefix + "%");
    return value == null ? 0 : value;
  }

  public double costByResourceForCurrentMonth(String resourceId) {
    String monthPrefix = currentMonthPrefix();
    Double value = jdbcTemplate.queryForObject("""
      SELECT COALESCE(SUM(cost_amount), 0)
      FROM cost_daily
      WHERE resource_id = ? AND stat_date LIKE ?
      """, Double.class, resourceId, monthPrefix + "%");
    return value == null ? 0 : value;
  }

  private String currentMonthPrefix() {
    return LocalDate.now().withDayOfMonth(1).toString().substring(0, 7);
  }

  private String normalizeResourceId(String resourceId) {
    return resourceId == null || resourceId.isBlank() ? "-" : resourceId;
  }

  private CostDaily mapCost(ResultSet resultSet) throws SQLException {
    return new CostDaily(
      resultSet.getString("service_name"),
      resultSet.getString("resource_id"),
      resultSet.getString("stat_date"),
      resultSet.getDouble("usage_amount"),
      resultSet.getString("usage_unit"),
      resultSet.getDouble("cost_amount"),
      resultSet.getString("currency")
    );
  }
}
