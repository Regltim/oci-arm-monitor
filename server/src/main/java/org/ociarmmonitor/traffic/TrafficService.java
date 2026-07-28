package org.ociarmmonitor.traffic;

import org.ociarmmonitor.config.FreeQuota;
import org.ociarmmonitor.config.FreeQuotaRepository;
import java.time.YearMonth;
import org.springframework.stereotype.Service;

@Service
public class TrafficService {

  private final TrafficRepository trafficRepository;
  private final FreeQuotaRepository freeQuotaRepository;

  public TrafficService(TrafficRepository trafficRepository, FreeQuotaRepository freeQuotaRepository) {
    this.trafficRepository = trafficRepository;
    this.freeQuotaRepository = freeQuotaRepository;
  }

  public TrafficSummary getSummary() {
    return getSummary(YearMonth.now());
  }

  public TrafficSummary getSummary(YearMonth month) {
    FreeQuota freeQuota = freeQuotaRepository.getQuota();
    double ingressGb = trafficRepository.sumIngressForMonth(month);
    double egressGb = trafficRepository.sumEgressForMonth(month);
    double percent = freeQuota.outboundDataTransferGb() == 0 ? 0 : egressGb / freeQuota.outboundDataTransferGb() * 100;
    return new TrafficSummary(
      ingressGb,
      egressGb,
      freeQuota.outboundDataTransferGb(),
      percent,
      trafficRepository.listMonth(month)
    );
  }
}
