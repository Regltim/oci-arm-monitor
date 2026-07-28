package org.ociarmmonitor.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WechatApiClientTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final AtomicInteger tokenRequests = new AtomicInteger();
  private final AtomicInteger messageRequests = new AtomicInteger();
  private final List<String> messageBodies = new CopyOnWriteArrayList<>();
  private HttpServer server;
  private String apiBaseUrl;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    apiBaseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void cachesAccessTokenAndSendsExpectedTemplatePayload() throws Exception {
    server.createContext("/cgi-bin/token", exchange -> {
      tokenRequests.incrementAndGet();
      respond(exchange, 200, "{\"access_token\":\"access-token-1\",\"expires_in\":7200}");
    });
    server.createContext("/cgi-bin/message/template/send", exchange -> {
      messageRequests.incrementAndGet();
      messageBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      respond(exchange, 200, "{\"errcode\":0,\"errmsg\":\"ok\",\"msgid\":123}");
    });
    server.start();
    WechatApiClient client = client();

    client.sendTemplate(settings(), "openid_example_1", WechatTemplateType.STATUS, message());
    client.sendTemplate(settings(), "openid_example_2", WechatTemplateType.COST_TRAFFIC, message());

    assertThat(tokenRequests).hasValue(1);
    assertThat(messageRequests).hasValue(2);
    JsonNode payload = objectMapper.readTree(messageBodies.get(0));
    assertThat(payload.path("touser").asText()).isEqualTo("openid_example_1");
    assertThat(payload.path("template_id").asText()).isEqualTo("template_example_status");
    assertThat(payload.has("url")).isFalse();
    assertThat(payload.has("miniprogram")).isFalse();
    JsonNode costPayload = objectMapper.readTree(messageBodies.get(1));
    assertThat(costPayload.path("template_id").asText()).isEqualTo("template_example_cost");
    JsonNode data = payload.path("data");
    assertThat(data.properties()).extracting(java.util.Map.Entry::getKey)
      .containsExactly("first", "item1", "item2", "item3");
    assertThat(data.path("first").path("value").asText()).isEqualTo("OCI ARM Monitor 测试通知");
    assertThat(data.path("item1").path("value").asText()).isEqualTo("信息");
    assertThat(data.path("item2").path("value").asText()).isEqualTo("通知通道");
    String item3 = data.path("item3").path("value").asText();
    assertThat(item3).startsWith("测试成功 ").doesNotContain("\n");
    assertThat(item3.codePointCount(0, item3.length())).isEqualTo(180);
  }

  @Test
  void refreshesAccessTokenWhenAppIdChanges() throws Exception {
    server.createContext("/cgi-bin/token", exchange -> {
      int requestNumber = tokenRequests.incrementAndGet();
      respond(exchange, 200, "{\"access_token\":\"access-token-" + requestNumber + "\",\"expires_in\":7200}");
    });
    server.createContext("/cgi-bin/message/template/send", exchange ->
      respond(exchange, 200, "{\"errcode\":0,\"errmsg\":\"ok\"}"));
    server.start();
    WechatApiClient client = client();

    client.sendTemplate(settings(), "openid_example_1", WechatTemplateType.STATUS, message());
    client.sendTemplate(settings("wx_another_app_id"), "openid_example_2", WechatTemplateType.STATUS, message());

    assertThat(tokenRequests).hasValue(2);
  }

  @Test
  void refreshesTokenOnceWhenWechatReportsExpiredToken() throws Exception {
    server.createContext("/cgi-bin/token", exchange -> {
      int requestNumber = tokenRequests.incrementAndGet();
      respond(exchange, 200, "{\"access_token\":\"access-token-" + requestNumber + "\",\"expires_in\":7200}");
    });
    server.createContext("/cgi-bin/message/template/send", exchange -> {
      int requestNumber = messageRequests.incrementAndGet();
      if (requestNumber == 1) {
        respond(exchange, 200, "{\"errcode\":40014,\"errmsg\":\"invalid access token\"}");
      } else {
        respond(exchange, 200, "{\"errcode\":0,\"errmsg\":\"ok\"}");
      }
    });
    server.start();

    client().sendTemplate(settings(), "openid_example_1", WechatTemplateType.STATUS, message());

    assertThat(tokenRequests).hasValue(2);
    assertThat(messageRequests).hasValue(2);
  }

  @Test
  void rejectsWechatErrorWithoutLeakingCredentials() throws Exception {
    server.createContext("/cgi-bin/token", exchange ->
      respond(exchange, 200, "{\"access_token\":\"access-token-1\",\"expires_in\":7200}"));
    server.createContext("/cgi-bin/message/template/send", exchange ->
      respond(exchange, 200, "{\"errcode\":40003,\"errmsg\":\"invalid template_example_status template_example_cost openid_example_1\"}"));
    server.start();

    assertThatThrownBy(() -> client().sendTemplate(
      settings(),
      "openid_example_1",
      WechatTemplateType.STATUS,
      message()
    ))
      .isInstanceOf(WechatApiException.class)
      .hasMessageNotContaining("template_example_status")
      .hasMessageNotContaining("template_example_cost")
      .hasMessageNotContaining("wx_example_secret")
      .hasMessageNotContaining("openid_example_1");
  }

  @Test
  void rejectsHttpAndMalformedJsonResponses() throws Exception {
    server.createContext("/cgi-bin/token", exchange -> respond(exchange, 503, "temporarily unavailable"));
    server.start();

    assertThatThrownBy(() -> client().sendTemplate(
      settings(),
      "openid_example_1",
      WechatTemplateType.STATUS,
      message()
    ))
      .isInstanceOf(WechatApiException.class)
      .hasMessage("微信接口请求失败，HTTP 状态码 503");

    server.stop(0);
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    apiBaseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext("/cgi-bin/token", exchange -> respond(exchange, 200, "not-json"));
    server.start();

    assertThatThrownBy(() -> client().sendTemplate(
      settings(),
      "openid_example_1",
      WechatTemplateType.STATUS,
      message()
    ))
      .isInstanceOf(WechatApiException.class)
      .hasMessage("微信接口返回了无法解析的数据");
  }

  private WechatApiClient client() {
    return new WechatApiClient(properties(), objectMapper);
  }

  private WechatNotificationProperties properties() {
    return new WechatNotificationProperties(
      true,
      "wx_example_app_id",
      "wx_example_secret",
      "template_example_status",
      "template_example_cost",
      "openid_example_1",
      true,
      false,
      "09:00",
      "Asia/Shanghai",
      apiBaseUrl
    );
  }

  private WechatNotificationSettings settings() {
    return settings("wx_example_app_id");
  }

  private WechatNotificationSettings settings(String appId) {
    return new WechatNotificationSettings(
      true,
      appId,
      "wx_example_secret",
      "template_example_status",
      "template_example_cost",
      List.of("openid_example_1"),
      "",
      true,
      false,
      LocalTime.of(9, 0),
      ZoneId.of("Asia/Shanghai"),
      "ENVIRONMENT",
      ""
    );
  }

  private WechatTemplateMessage message() {
    return new WechatTemplateMessage(
      "OCI ARM Monitor 测试通知",
      "信息",
      "通知通道",
      "测试成功\n" + "详".repeat(200)
    );
  }

  private void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }
}
