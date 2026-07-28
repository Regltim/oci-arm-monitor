package org.ociarmmonitor.publicreport;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ociarmmonitor.common.GlobalExceptionHandler;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PublicReportControllerTest {

  private StubPublicReportService publicReportService;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    publicReportService = new StubPublicReportService();
    mockMvc = MockMvcBuilders
      .standaloneSetup(new PublicReportController(publicReportService))
      .setControllerAdvice(new GlobalExceptionHandler())
      .build();
  }

  @Test
  void returnsSnapshotWithPrivateCacheAndIndexingHeaders() throws Exception {
    PublicReportView view = new PublicReportView(
      "snapshot-example",
      "2026-07-28T01:00:00Z",
      "2026-07-29T01:00:00Z",
      null
    );
    publicReportService.result = Optional.of(view);

    mockMvc.perform(get("/public/reports/snapshot-example")
        .header("Authorization", "Bearer token-example"))
      .andExpect(status().isOk())
      .andExpect(header().string("Cache-Control", "no-store"))
      .andExpect(header().string("X-Robots-Tag", "noindex"))
      .andExpect(header().string("Referrer-Policy", "no-referrer"))
      .andExpect(jsonPath("$.data.id").value("snapshot-example"));
  }

  @Test
  void returnsTheSameNotFoundResponseForMissingAndInvalidTokens() throws Exception {
    publicReportService.result = Optional.empty();

    mockMvc.perform(get("/public/reports/snapshot-example"))
      .andExpect(status().isNotFound())
      .andExpect(header().string("Cache-Control", "no-store"))
      .andExpect(jsonPath("$.message").value("报告不存在或已过期"));
    mockMvc.perform(get("/public/reports/snapshot-example")
        .header("Authorization", "Bearer wrong-token"))
      .andExpect(status().isNotFound())
      .andExpect(header().string("Cache-Control", "no-store"))
      .andExpect(jsonPath("$.message").value("报告不存在或已过期"));
  }

  private static class StubPublicReportService extends PublicReportService {

    private Optional<PublicReportView> result = Optional.empty();

    StubPublicReportService() {
      super(
        new JdbcTemplate(),
        new ObjectMapper(),
        new PublicReportSnapshotMapper(),
        Clock.systemUTC(),
        new SecureRandom()
      );
    }

    @Override
    public Optional<PublicReportView> find(String snapshotId, String token) {
      return result;
    }
  }
}
