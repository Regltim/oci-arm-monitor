package org.ociarmmonitor.oci;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

@Service
public class ScheduledSyncService {

  private static final Logger LOGGER = LoggerFactory.getLogger(ScheduledSyncService.class);

  private final SyncScheduleRepository syncScheduleRepository;
  private final OciSyncService ociSyncService;
  private final TaskScheduler taskScheduler;
  private ScheduledFuture<?> scheduledFuture;

  public ScheduledSyncService(
    SyncScheduleRepository syncScheduleRepository,
    OciSyncService ociSyncService,
    @Qualifier("monitorTaskScheduler") TaskScheduler taskScheduler
  ) {
    this.syncScheduleRepository = syncScheduleRepository;
    this.ociSyncService = ociSyncService;
    this.taskScheduler = taskScheduler;
  }

  @EventListener(ApplicationReadyEvent.class)
  public synchronized void start() {
    reschedule();
    SyncSchedule schedule = getSchedule();
    if (schedule.enabled() && schedule.syncOnStartup()) {
      LOGGER.info("Start scheduled OCI sync on application startup.");
      ociSyncService.syncScheduledResources();
    }
  }

  public synchronized SyncSchedule getSchedule() {
    return withNextRunAt(syncScheduleRepository.get());
  }

  public synchronized SyncSchedule updateSchedule(SyncScheduleUpdateRequest request) {
    validate(request.cronExpression(), request.zoneId());
    SyncSchedule schedule = syncScheduleRepository.update(request);
    reschedule();
    return withNextRunAt(schedule);
  }

  private void reschedule() {
    if (scheduledFuture != null) {
      scheduledFuture.cancel(false);
      scheduledFuture = null;
    }
    SyncSchedule schedule = syncScheduleRepository.get();
    if (!schedule.enabled()) {
      LOGGER.info("OCI scheduled sync is disabled.");
      return;
    }
    validate(schedule.cronExpression(), schedule.zoneId());
    scheduledFuture = taskScheduler.schedule(
      this::runScheduledSync,
      new CronTrigger(schedule.cronExpression(), ZoneId.of(schedule.zoneId()))
    );
    LOGGER.info("OCI scheduled sync registered cron={} zone={}", schedule.cronExpression(), schedule.zoneId());
  }

  private void runScheduledSync() {
    LOGGER.info("Start scheduled OCI sync.");
    ociSyncService.syncScheduledResources();
  }

  private SyncSchedule withNextRunAt(SyncSchedule schedule) {
    if (!schedule.enabled()) {
      return new SyncSchedule(false, schedule.cronExpression(), schedule.zoneId(), schedule.syncOnStartup(), schedule.updatedAt(), "");
    }
    try {
      ZonedDateTime nextRun = CronExpression.parse(schedule.cronExpression())
        .next(ZonedDateTime.now(ZoneId.of(schedule.zoneId())));
      return new SyncSchedule(
        true,
        schedule.cronExpression(),
        schedule.zoneId(),
        schedule.syncOnStartup(),
        schedule.updatedAt(),
        nextRun == null ? "" : nextRun.toInstant().toString()
      );
    } catch (RuntimeException exception) {
      return new SyncSchedule(
        schedule.enabled(),
        schedule.cronExpression(),
        schedule.zoneId(),
        schedule.syncOnStartup(),
        schedule.updatedAt(),
        ""
      );
    }
  }

  private void validate(String cronExpression, String zoneId) {
    try {
      CronExpression.parse(cronExpression);
      ZoneId.of(zoneId);
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("定时同步配置不合法：" + exception.getMessage(), exception);
    }
  }
}
