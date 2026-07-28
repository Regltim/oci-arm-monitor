package org.ociarmmonitor.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthInterceptorTest {

  @Test
  void allowsPublicReportWithoutSessionButKeepsOtherApisProtected() throws Exception {
    AuthInterceptor interceptor = new AuthInterceptor(new ObjectMapper());
    MockHttpServletRequest publicRequest = new MockHttpServletRequest(
      "GET",
      "/api/public/reports/snapshot-example"
    );
    publicRequest.setContextPath("/api");
    MockHttpServletResponse publicResponse = new MockHttpServletResponse();
    MockHttpServletRequest protectedRequest = new MockHttpServletRequest("GET", "/api/dashboard/summary");
    protectedRequest.setContextPath("/api");
    MockHttpServletResponse protectedResponse = new MockHttpServletResponse();

    assertThat(interceptor.preHandle(publicRequest, publicResponse, new Object())).isTrue();
    assertThat(interceptor.preHandle(protectedRequest, protectedResponse, new Object())).isFalse();
    assertThat(protectedResponse.getStatus()).isEqualTo(401);
  }
}
