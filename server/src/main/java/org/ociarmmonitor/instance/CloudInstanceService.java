package org.ociarmmonitor.instance;

import org.ociarmmonitor.cost.CostRepository;
import org.ociarmmonitor.traffic.TrafficRepository;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class CloudInstanceService {

  private final CloudInstanceRepository cloudInstanceRepository;
  private final MetricRepository metricRepository;
  private final TrafficRepository trafficRepository;
  private final CostRepository costRepository;

  public CloudInstanceService(
    CloudInstanceRepository cloudInstanceRepository,
    MetricRepository metricRepository,
    TrafficRepository trafficRepository,
    CostRepository costRepository
  ) {
    this.cloudInstanceRepository = cloudInstanceRepository;
    this.metricRepository = metricRepository;
    this.trafficRepository = trafficRepository;
    this.costRepository = costRepository;
  }

  public List<InstanceOverview> listInstances() {
    LocalDate today = LocalDate.now();
    return cloudInstanceRepository.findAll().stream()
      .map(instance -> new InstanceOverview(
        instance,
        metricRepository.latest(instance.id(), "cpu_utilization"),
        metricRepository.latest(instance.id(), "memory_utilization"),
        trafficRepository.egressByInstanceAndDate(instance.id(), today),
        costRepository.costByResourceForCurrentMonth(instance.id())
      ))
      .toList();
  }

  public List<MetricPoint> listMetricSeries(String instanceId, String metricName) {
    return metricRepository.findSeries(instanceId, metricName, 48);
  }
}
