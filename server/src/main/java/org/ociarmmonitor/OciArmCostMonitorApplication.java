package org.ociarmmonitor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class OciArmCostMonitorApplication {

  public static void main(String[] args) {
    SpringApplication.run(OciArmCostMonitorApplication.class, args);
  }
}
