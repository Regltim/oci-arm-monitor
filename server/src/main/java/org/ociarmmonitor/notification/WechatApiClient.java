package org.ociarmmonitor.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class WechatApiClient implements WechatTemplateSender {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private static final long TOKEN_REFRESH_MARGIN_SECONDS = 300;

  private final WechatNotificationProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private volatile CachedToken cachedToken;

  public WechatApiClient(WechatNotificationProperties properties, ObjectMapper objectMapper) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.httpClient = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .build();
  }

  @Override
  public void sendTemplate(
    WechatNotificationSettings settings,
    String openId,
    WechatTemplateType templateType,
    WechatTemplateMessage message
  ) {
    String accessToken = accessToken(settings, false);
    try {
      sendTemplateRequest(settings, openId, templateType, message, accessToken);
    } catch (WechatApiException exception) {
      if (!isExpiredTokenError(exception.errorCode())) {
        throw exception;
      }
      invalidateToken(accessToken);
      sendTemplateRequest(settings, openId, templateType, message, accessToken(settings, true));
    }
  }

  private synchronized String accessToken(WechatNotificationSettings settings, boolean forceRefresh) {
    Instant now = Instant.now();
    if (!forceRefresh
      && cachedToken != null
      && cachedToken.appId().equals(settings.appId())
      && cachedToken.expiresAt().isAfter(now)) {
      return cachedToken.value();
    }

    String query = "grant_type=client_credential&appid="
      + encode(settings.appId())
      + "&secret="
      + encode(settings.appSecret());
    JsonNode response = sendJson(
      HttpRequest.newBuilder(endpoint("/cgi-bin/token?" + query))
        .timeout(REQUEST_TIMEOUT)
        .GET()
        .build()
    );
    int errorCode = response.path("errcode").asInt(0);
    if (errorCode != 0) {
      throw new WechatApiException(
        "微信 access_token 获取失败，错误码 " + errorCode + formatWechatMessage(response, settings, ""),
        errorCode
      );
    }
    String token = response.path("access_token").asText("").trim();
    long expiresIn = response.path("expires_in").asLong(0);
    if (token.isBlank() || expiresIn <= 0) {
      throw new WechatApiException("微信 access_token 响应缺少必要字段");
    }
    long cacheSeconds = Math.max(1, expiresIn - TOKEN_REFRESH_MARGIN_SECONDS);
    cachedToken = new CachedToken(settings.appId(), token, now.plusSeconds(cacheSeconds));
    return token;
  }

  private void sendTemplateRequest(
    WechatNotificationSettings settings,
    String openId,
    WechatTemplateType templateType,
    WechatTemplateMessage message,
    String accessToken
  ) {
    String requestBody;
    try {
      requestBody = objectMapper.writeValueAsString(templatePayload(settings, openId, templateType, message));
    } catch (JsonProcessingException exception) {
      throw new WechatApiException("微信公众号模板消息生成失败", exception);
    }
    JsonNode response = sendJson(
      HttpRequest.newBuilder(endpoint("/cgi-bin/message/template/send?access_token=" + encode(accessToken)))
        .timeout(REQUEST_TIMEOUT)
        .header("Content-Type", "application/json; charset=utf-8")
        .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
        .build()
    );
    int errorCode = response.path("errcode").asInt(-1);
    if (errorCode != 0) {
      throw new WechatApiException(
        "微信模板消息发送失败，错误码 " + errorCode + formatWechatMessage(response, settings, openId),
        errorCode
      );
    }
  }

  private Map<String, Object> templatePayload(
    WechatNotificationSettings settings,
    String openId,
    WechatTemplateType templateType,
    WechatTemplateMessage message
  ) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("first", value(message.first()));
    data.put("level", value(message.level()));
    data.put("metric", value(message.metric()));
    data.put("status", value(message.status()));
    data.put("content", value(message.content()));
    data.put("time", value(message.time()));
    data.put("remark", value(message.remark()));

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("touser", openId);
    payload.put("template_id", templateId(settings, templateType));
    payload.put("data", data);
    return payload;
  }

  private Map<String, String> value(String value) {
    return Map.of("value", value == null ? "" : value);
  }

  private JsonNode sendJson(HttpRequest request) {
    HttpResponse<String> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new WechatApiException("微信接口请求已中断", exception);
    } catch (IOException exception) {
      throw new WechatApiException("微信接口暂时无法访问", exception);
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new WechatApiException("微信接口请求失败，HTTP 状态码 " + response.statusCode());
    }
    try {
      return objectMapper.readTree(response.body());
    } catch (JsonProcessingException exception) {
      throw new WechatApiException("微信接口返回了无法解析的数据", exception);
    }
  }

  private URI endpoint(String pathAndQuery) {
    String baseUrl = properties.apiBaseUrl() == null ? "" : properties.apiBaseUrl().trim();
    while (baseUrl.endsWith("/")) {
      baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
    }
    return URI.create(baseUrl + pathAndQuery);
  }

  private String formatWechatMessage(
    JsonNode response,
    WechatNotificationSettings settings,
    String openId
  ) {
    String upstreamMessage = response.path("errmsg").asText("").replaceAll("[\\r\\n]+", " ").trim();
    upstreamMessage = redact(upstreamMessage, settings.appId());
    upstreamMessage = redact(upstreamMessage, settings.appSecret());
    upstreamMessage = redact(upstreamMessage, settings.templateId());
    upstreamMessage = redact(upstreamMessage, settings.costTemplateId());
    upstreamMessage = redact(upstreamMessage, openId);
    if (upstreamMessage.length() > 160) {
      upstreamMessage = upstreamMessage.substring(0, 160);
    }
    return upstreamMessage.isBlank() ? "" : "：" + upstreamMessage;
  }

  private String redact(String value, String secret) {
    return secret == null || secret.isBlank() ? value : value.replace(secret, "[REDACTED]");
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private boolean isExpiredTokenError(Integer errorCode) {
    return Integer.valueOf(40014).equals(errorCode) || Integer.valueOf(42001).equals(errorCode);
  }

  private String templateId(WechatNotificationSettings settings, WechatTemplateType templateType) {
    return switch (templateType) {
      case STATUS -> settings.templateId();
      case COST_TRAFFIC -> settings.costTemplateId();
    };
  }

  private synchronized void invalidateToken(String token) {
    if (cachedToken != null && cachedToken.value().equals(token)) {
      cachedToken = null;
    }
  }

  private record CachedToken(String appId, String value, Instant expiresAt) {
  }
}
