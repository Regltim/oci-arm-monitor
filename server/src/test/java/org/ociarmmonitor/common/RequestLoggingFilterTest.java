package org.ociarmmonitor.common;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestLoggingFilterTest {

  @Test
  void writesIncomingRequestIdToResponseAndMdcDuringRequest() throws Exception {
    RequestLoggingFilter filter = new RequestLoggingFilter();
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/dashboard/summary");
    MockHttpServletResponse response = new MockHttpServletResponse();
    request.addHeader(RequestLoggingFilter.REQUEST_ID_HEADER, "request-123");

    FilterChain chain = (servletRequest, servletResponse) -> {
      assertThat(MDC.get(RequestLoggingFilter.MDC_REQUEST_ID)).isEqualTo("request-123");
    };

    filter.doFilter(request, response, chain);

    assertThat(response.getHeader(RequestLoggingFilter.REQUEST_ID_HEADER)).isEqualTo("request-123");
    assertThat(MDC.get(RequestLoggingFilter.MDC_REQUEST_ID)).isNull();
  }

  @Test
  void generatesRequestIdWhenHeaderIsMissing() throws Exception {
    RequestLoggingFilter filter = new RequestLoggingFilter();
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/sync/full");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, (servletRequest, servletResponse) -> {
      assertThat(MDC.get(RequestLoggingFilter.MDC_REQUEST_ID)).isNotBlank();
    });

    assertThat(response.getHeader(RequestLoggingFilter.REQUEST_ID_HEADER)).isNotBlank();
    assertThat(MDC.get(RequestLoggingFilter.MDC_REQUEST_ID)).isNull();
  }
}
