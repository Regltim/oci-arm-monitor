package org.ociarmmonitor.publicreport;

import org.ociarmmonitor.common.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/public/reports")
public class PublicReportController {

  private final PublicReportService publicReportService;

  public PublicReportController(PublicReportService publicReportService) {
    this.publicReportService = publicReportService;
  }

  @GetMapping("/{snapshotId}")
  public ResponseEntity<ApiResponse<PublicReportView>> getReport(
    @PathVariable String snapshotId,
    @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
  ) {
    String token = bearerToken(authorization);
    PublicReportView report = publicReportService.find(snapshotId, token)
      .orElseThrow(PublicReportNotFoundException::new);
    return ResponseEntity.ok()
      .cacheControl(CacheControl.noStore())
      .header("X-Robots-Tag", "noindex")
      .header("Referrer-Policy", "no-referrer")
      .body(ApiResponse.ok(report));
  }

  private String bearerToken(String authorization) {
    if (authorization == null || !authorization.startsWith("Bearer ")) {
      throw new PublicReportNotFoundException();
    }
    String token = authorization.substring("Bearer ".length()).trim();
    if (token.isBlank()) {
      throw new PublicReportNotFoundException();
    }
    return token;
  }
}
