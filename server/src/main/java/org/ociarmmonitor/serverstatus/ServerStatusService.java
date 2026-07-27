package org.ociarmmonitor.serverstatus;

import org.ociarmmonitor.oci.SyncRunRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ServerStatusService {

  private final ServerStatusCollector serverStatusCollector;
  private final ServerStatusRepository serverStatusRepository;
  private final ServerAlertService serverAlertService;
  private final SyncRunRepository syncRunRepository;

  public ServerStatusService(
    ServerStatusCollector serverStatusCollector,
    ServerStatusRepository serverStatusRepository,
    ServerAlertService serverAlertService,
    SyncRunRepository syncRunRepository
  ) {
    this.serverStatusCollector = serverStatusCollector;
    this.serverStatusRepository = serverStatusRepository;
    this.serverAlertService = serverAlertService;
    this.syncRunRepository = syncRunRepository;
  }

  public ServerStatusSummary getSummary() {
    ServerStatusSnapshot snapshot = sampleAndStore();
    return new ServerStatusSummary(
      snapshot,
      listHistory(24, 720),
      serverAlertService.evaluate(snapshot, syncRunRepository.latest()),
      serverStatusCollector.collectSystemInfo()
    );
  }

  public ServerStatusSnapshot sampleAndStore() {
    ServerStatusSnapshot snapshot = serverStatusCollector.sample();
    serverStatusRepository.save(snapshot);
    serverStatusRepository.deleteExpired();
    return snapshot;
  }

  public List<ServerMetricPoint> listHistory(int hours, int limit) {
    return serverStatusRepository.listHistory(hours, limit);
  }
}
