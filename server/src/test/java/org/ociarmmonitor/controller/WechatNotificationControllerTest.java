package org.ociarmmonitor.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ociarmmonitor.common.GlobalExceptionHandler;
import org.ociarmmonitor.notification.WechatDeliveryLogRepository;
import org.ociarmmonitor.notification.WechatDeliveryResult;
import org.ociarmmonitor.notification.WechatNotificationProperties;
import org.ociarmmonitor.notification.WechatNotificationService;
import org.ociarmmonitor.notification.WechatNotificationSettingsRepository;
import org.ociarmmonitor.notification.WechatSecretCipher;
import org.ociarmmonitor.notification.WechatTemplateMessage;
import org.ociarmmonitor.notification.WechatTemplateSender;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WechatNotificationControllerTest {

  private static final String VALID_KEY = Base64.getEncoder().encodeToString(
    "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)
  );

  private final ObjectMapper objectMapper = new ObjectMapper();
  private MockMvc mockMvc;
  private WechatDeliveryLogRepository deliveryLogRepository;
  private int sentMessages;

  @BeforeEach
  void setUp() {
    SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    WechatNotificationSettingsRepository settingsRepository = new WechatNotificationSettingsRepository(
      jdbcTemplate,
      properties(),
      new WechatSecretCipher(VALID_KEY)
    );
    WechatTemplateSender templateSender = new WechatTemplateSender() {
      @Override
      public void sendTemplate(
        org.ociarmmonitor.notification.WechatNotificationSettings settings,
        String openId,
        WechatTemplateMessage message
      ) {
        sentMessages++;
      }
    };
    WechatNotificationService notificationService = new WechatNotificationService(
      settingsRepository,
      templateSender
    );
    deliveryLogRepository = new WechatDeliveryLogRepository(jdbcTemplate);
    WechatNotificationController controller = new WechatNotificationController(
      settingsRepository,
      notificationService,
      deliveryLogRepository
    );
    mockMvc = MockMvcBuilders.standaloneSetup(controller)
      .setControllerAdvice(new GlobalExceptionHandler())
      .build();
  }

  @Test
  void returnsSafeMaskedSettingsWithoutSecretsOrRecipients() throws Exception {
    MvcResult mvcResult = mockMvc.perform(get("/settings/wechat"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.success").value(true))
      .andExpect(jsonPath("$.data.configured").value(true))
      .andExpect(jsonPath("$.data.appIdMasked").value("wx_e****p_id"))
      .andExpect(jsonPath("$.data.appSecretConfigured").value(true))
      .andExpect(jsonPath("$.data.recipientCount").value(2))
      .andReturn();

    String body = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(body)
      .doesNotContain("wx_example_secret")
      .doesNotContain("openid_example_1")
      .doesNotContain("openIds")
      .doesNotContain("appSecret\"");
  }

  @Test
  void updatesSettingsAndKeepsCredentialFieldsWriteOnly() throws Exception {
    String requestBody = """
      {
        "enabled": true,
        "appId": "wx_database_app",
        "appSecret": "database-secret",
        "templateId": "database-template",
        "openIds": "openid_database_1,openid_database_2",
        "publicUrl": "https://dashboard.example.com",
        "immediatePushEnabled": false,
        "dailySummaryEnabled": true,
        "dailySummaryTime": "21:30",
        "zoneId": "Asia/Shanghai"
      }
      """;

    MvcResult mvcResult = mockMvc.perform(put("/settings/wechat")
        .contentType("application/json")
        .content(requestBody))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.source").value("DATABASE"))
      .andExpect(jsonPath("$.data.immediatePushEnabled").value(false))
      .andExpect(jsonPath("$.data.dailySummaryEnabled").value(true))
      .andExpect(jsonPath("$.data.dailySummaryTime").value("21:30"))
      .andReturn();

    String body = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(body)
      .doesNotContain("database-secret")
      .doesNotContain("openid_database_1");
  }

  @Test
  void sendsManualTestAndRecordsSanitizedDelivery() throws Exception {
    mockMvc.perform(post("/settings/wechat/test"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.successCount").value(2))
      .andExpect(jsonPath("$.data.failureCount").value(0));

    assertThat(sentMessages).isEqualTo(2);
    assertThat(deliveryLogRepository.listRecent(20)).hasSize(1);
  }

  @Test
  void rejectsInvalidScheduleAndLimitsDeliveryHistoryToTwentyRows() throws Exception {
    String invalidRequest = """
      {
        "enabled": false,
        "appId": "",
        "appSecret": "",
        "templateId": "",
        "openIds": "",
        "publicUrl": "https://monitor.example.com",
        "immediatePushEnabled": true,
        "dailySummaryEnabled": true,
        "dailySummaryTime": "25:00",
        "zoneId": "Asia/Shanghai"
      }
      """;
    mockMvc.perform(put("/settings/wechat")
        .contentType("application/json")
        .content(invalidRequest))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.message").value("每日推送时间格式必须为 HH:mm"));

    for (int index = 0; index < 25; index++) {
      deliveryLogRepository.save(new WechatDeliveryResult(
        "TEST",
        "",
        1,
        0,
        "发送完成：成功 1，失败 0",
        Instant.ofEpochSecond(index).toString()
      ));
    }
    MvcResult mvcResult = mockMvc.perform(get("/settings/wechat/deliveries"))
      .andExpect(status().isOk())
      .andReturn();
    JsonNode response = objectMapper.readTree(mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
    assertThat(response.path("data").size()).isEqualTo(20);
  }

  private WechatNotificationProperties properties() {
    return new WechatNotificationProperties(
      true,
      "wx_example_app_id",
      "wx_example_secret",
      "template_example_01",
      "openid_example_1,openid_example_2",
      true,
      false,
      "09:00",
      "Asia/Shanghai",
      "https://monitor.example.com",
      "https://api.weixin.qq.com"
    );
  }
}
