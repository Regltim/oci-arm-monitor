package org.ociarmmonitor.publicreport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PublicReportCleanupScheduler {

  private static final Logger LOGGER = LoggerFactory.getLogger(PublicReportCleanupScheduler.class);

  private final PublicReportService publicReportService;

  public PublicReportCleanupScheduler(PublicReportService publicReportService) {
    this.publicReportService = publicReportService;
  }

  @Scheduled(cron = "0 15 3 * * *", zone = "UTC")
  public void cleanupExpiredReports() {
    try {
      int deletedRows = publicReportService.cleanupExpired();
      if (deletedRows > 0) {
        LOGGER.info("已清理 {} 条过期公开日报数据", deletedRows);
      }
    } catch (RuntimeException exception) {
      LOGGER.warn("公开日报过期数据清理失败");
    }
  }
}
