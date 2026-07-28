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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class WechatApiClient implements WechatTemplateSender {

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration TEMPLATE_CACHE_TTL = Duration.ofMinutes(10);
  private static final long TOKEN_REFRESH_MARGIN_SECONDS = 300;
  private static final int MAX_TEMPLATE_FIELD_CODE_POINTS = 180;
  private static final int REQUIRED_TEMPLATE_FIELD_COUNT = 4;
  private static final Pattern TEMPLATE_FIELD_PATTERN = Pattern.compile(
    "\\{\\{\\s*([A-Za-z][A-Za-z0-9_]*)\\.DATA\\s*}}"
  );

  private final WechatNotificationProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private volatile CachedToken cachedToken;
  private volatile CachedTemplates cachedTemplates;

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
    String configuredTemplateId = templateId(settings, templateType);
    List<String> fieldNames = templateFields(
      settings,
      templateType,
      configuredTemplateId,
      accessToken
    );
    String requestBody;
    try {
      requestBody = objectMapper.writeValueAsString(
        templatePayload(openId, configuredTemplateId, fieldNames, message)
      );
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
    String openId,
    String configuredTemplateId,
    List<String> fieldNames,
    WechatTemplateMessage message
  ) {
    Map<String, Object> data = new LinkedHashMap<>();
    List<String> values = new ArrayList<>(REQUIRED_TEMPLATE_FIELD_COUNT);
    values.add(message.first());
    values.add(message.item1());
    values.add(message.item2());
    values.add(message.item3());
    for (int index = 0; index < REQUIRED_TEMPLATE_FIELD_COUNT; index++) {
      data.put(fieldNames.get(index), value(values.get(index)));
    }

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("touser", openId);
    payload.put("template_id", configuredTemplateId);
    payload.put("data", data);
    return payload;
  }

  private synchronized List<String> templateFields(
    WechatNotificationSettings settings,
    WechatTemplateType templateType,
    String configuredTemplateId,
    String accessToken
  ) {
    Instant now = Instant.now();
    if (cachedTemplates != null
      && cachedTemplates.appId().equals(settings.appId())
      && cachedTemplates.expiresAt().isAfter(now)
      && cachedTemplates.fieldsByTemplateId().containsKey(configuredTemplateId)) {
      return requireFourFields(templateType, cachedTemplates.fieldsByTemplateId().get(configuredTemplateId));
    }

    JsonNode response = sendJson(
      HttpRequest.newBuilder(endpoint(
          "/cgi-bin/template/get_all_private_template?access_token=" + encode(accessToken)
        ))
        .timeout(REQUEST_TIMEOUT)
        .GET()
        .build()
    );
    int errorCode = response.path("errcode").asInt(0);
    if (errorCode != 0) {
      throw new WechatApiException(
        "微信模板列表获取失败，错误码 "
          + errorCode
          + formatWechatMessage(response, settings, ""),
        errorCode
      );
    }

    JsonNode templateList = response.path("template_list");
    if (!templateList.isArray()) {
      throw new WechatApiException("微信模板列表响应缺少 template_list");
    }
    Map<String, List<String>> fieldsByTemplateId = new LinkedHashMap<>();
    for (JsonNode template : templateList) {
      String templateId = template.path("template_id").asText("").trim();
      if (!templateId.isBlank()) {
        fieldsByTemplateId.put(templateId, extractTemplateFields(template.path("content").asText("")));
      }
    }
    Map<String, List<String>> immutableFields = Collections.unmodifiableMap(fieldsByTemplateId);
    cachedTemplates = new CachedTemplates(settings.appId(), immutableFields, now.plus(TEMPLATE_CACHE_TTL));

    List<String> fieldNames = immutableFields.get(configuredTemplateId);
    if (fieldNames == null) {
      throw new WechatApiException(
        "微信后台未找到已配置的" + templateTypeLabel(templateType) + "模板，请确认 Template ID 属于当前公众号"
      );
    }
    return requireFourFields(templateType, fieldNames);
  }

  private List<String> extractTemplateFields(String content) {
    Set<String> fieldNames = new LinkedHashSet<>();
    Matcher matcher = TEMPLATE_FIELD_PATTERN.matcher(content == null ? "" : content);
    while (matcher.find()) {
      fieldNames.add(matcher.group(1));
    }
    return List.copyOf(fieldNames);
  }

  private List<String> requireFourFields(WechatTemplateType templateType, List<String> fieldNames) {
    if (fieldNames.size() != REQUIRED_TEMPLATE_FIELD_COUNT) {
      throw new WechatApiException(
        "微信"
          + templateTypeLabel(templateType)
          + "模板需要正好 4 个不同的数据字段，当前识别到 "
          + fieldNames.size()
          + " 个"
      );
    }
    return fieldNames;
  }

  private String templateTypeLabel(WechatTemplateType templateType) {
    return switch (templateType) {
      case STATUS -> "运行状态";
      case COST_TRAFFIC -> "费用与流量";
    };
  }

  private Map<String, String> value(String value) {
    String normalized = value == null
      ? ""
      : value.replaceAll("[\\p{Cntrl}\\r\\n\\t]+", " ").replaceAll("\\s+", " ").trim();
    if (normalized.codePointCount(0, normalized.length()) > MAX_TEMPLATE_FIELD_CODE_POINTS) {
      normalized = normalized.substring(0, normalized.offsetByCodePoints(0, MAX_TEMPLATE_FIELD_CODE_POINTS));
    }
    return Map.of("value", normalized);
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

  private record CachedTemplates(
    String appId,
    Map<String, List<String>> fieldsByTemplateId,
    Instant expiresAt
  ) {
  }
}
