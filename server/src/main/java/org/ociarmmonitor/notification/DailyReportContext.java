package org.ociarmmonitor.notification;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

public record DailyReportContext(
  Instant reportAt,
  ZoneId zoneId,
  LocalDate localDate,
  YearMonth yearMonth
) {

  public static DailyReportContext from(Clock clock, ZoneId zoneId) {
    Instant reportAt = clock.instant();
    LocalDate localDate = reportAt.atZone(zoneId).toLocalDate();
    return new DailyReportContext(reportAt, zoneId, localDate, YearMonth.from(localDate));
  }
}
