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
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ociarmmonitor.common.GlobalExceptionHandler;
import org.ociarmmonitor.config.FreeQuotaRepository;
import org.ociarmmonitor.cost.CostRepository;
import org.ociarmmonitor.cost.CostService;
import org.ociarmmonitor.cost.ManualCostRepository;
import org.ociarmmonitor.instance.CloudInstanceRepository;
import org.ociarmmonitor.instance.MetricRepository;
import org.ociarmmonitor.notification.DailyReportDataProvider;
import org.ociarmmonitor.notification.WechatDeliveryLogRepository;
import org.ociarmmonitor.notification.WechatDeliveryResult;
import org.ociarmmonitor.notification.WechatNotificationProperties;
import org.ociarmmonitor.notification.WechatNotificationService;
import org.ociarmmonitor.notification.WechatNotificationSettingsRepository;
import org.ociarmmonitor.notification.WechatSecretCipher;
import org.ociarmmonitor.notification.WechatTemplateMessage;
import org.ociarmmonitor.notification.WechatTemplateSender;
import org.ociarmmonitor.notification.WechatTemplateType;
import org.ociarmmonitor.oci.SyncRunRepository;
import org.ociarmmonitor.publicreport.PublicReportService;
import org.ociarmmonitor.publicreport.PublicReportSnapshotMapper;
import org.ociarmmonitor.serverstatus.AlertRuleRepository;
import org.ociarmmonitor.serverstatus.ServerAlertService;
import org.ociarmmonitor.serverstatus.ServerStatusRepository;
import org.ociarmmonitor.traffic.TrafficRepository;
import org.ociarmmonitor.traffic.TrafficService;
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
    configure(properties(true, "template_example_cost"));
  }

  @Test
  void returnsSafeMaskedSettingsWithoutSecretsOrRecipients() throws Exception {
    MvcResult mvcResult = mockMvc.perform(get("/settings/wechat"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.success").value(true))
      .andExpect(jsonPath("$.data.configured").value(true))
      .andExpect(jsonPath("$.data.appIdMasked").value("wx_e****p_id"))
      .andExpect(jsonPath("$.data.appSecretConfigured").value(true))
      .andExpect(jsonPath("$.data.costTemplateIdMasked").value("temp****cost"))
      .andExpect(jsonPath("$.data.dailySummaryConfigured").value(true))
      .andExpect(jsonPath("$.data.recipientCount").value(2))
      .andReturn();

    String body = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(body)
      .doesNotContain("wx_example_secret")
      .doesNotContain("openid_example_1")
      .doesNotContain("openIds")
      .doesNotContain("appSecret\"")
      .doesNotContain("publicUrl");
  }

  @Test
  void updatesSettingsAndKeepsCredentialFieldsWriteOnly() throws Exception {
    String requestBody = """
      {
        "enabled": true,
        "appId": "wx_database_app",
        "appSecret": "database-secret",
        "templateId": "database-template",
        "costTemplateId": "database-cost-template",
        "openIds": "openid_database_1,openid_database_2",
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
  void pushesCurrentDataAndRecordsSeparateSanitizedDeliveries() throws Exception {
    MvcResult mvcResult = mockMvc.perform(post("/settings/wechat/test"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.status.notificationType").value("TEST_STATUS"))
      .andExpect(jsonPath("$.data.status.successCount").value(2))
      .andExpect(jsonPath("$.data.costTraffic.notificationType").value("TEST_COST_TRAFFIC"))
      .andExpect(jsonPath("$.data.costTraffic.successCount").value(2))
      .andExpect(jsonPath("$.data.successCount").value(4))
      .andExpect(jsonPath("$.data.failureCount").value(0))
      .andReturn();

    assertThat(sentMessages).isEqualTo(4);
    assertThat(deliveryLogRepository.listRecent(20)).extracting(WechatDeliveryResult::notificationType)
      .containsExactly("TEST_COST_TRAFFIC", "TEST_STATUS");
    String body = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
    assertThat(body)
      .doesNotContain("wx_example_secret")
      .doesNotContain("openid_example_1")
      .doesNotContain("template_example_status")
      .doesNotContain("template_example_cost");
  }

  @Test
  void returnsPartialSuccessWhenCostTemplateIsMissing() throws Exception {
    configure(properties(true, ""));

    mockMvc.perform(post("/settings/wechat/test"))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.data.status.successCount").value(2))
      .andExpect(jsonPath("$.data.status.failureCount").value(0))
      .andExpect(jsonPath("$.data.costTraffic.successCount").value(0))
      .andExpect(jsonPath("$.data.costTraffic.failureCount").value(2))
      .andExpect(jsonPath("$.data.costTraffic.message").value("费用与流量模板未配置"))
      .andExpect(jsonPath("$.data.successCount").value(2))
      .andExpect(jsonPath("$.data.failureCount").value(2));

    assertThat(sentMessages).isEqualTo(2);
    assertThat(deliveryLogRepository.listRecent(20)).extracting(WechatDeliveryResult::notificationType)
      .containsExactly("TEST_COST_TRAFFIC", "TEST_STATUS");
  }

  @Test
  void rejectsManualTestWhenBaseConfigurationIsUnavailable() throws Exception {
    configure(properties(false, "template_example_cost"));

    mockMvc.perform(post("/settings/wechat/test"))
      .andExpect(status().isBadRequest())
      .andExpect(jsonPath("$.message").value("微信公众号通知尚未启用"));

    assertThat(sentMessages).isZero();
    assertThat(deliveryLogRepository.listRecent(20)).isEmpty();
  }

  @Test
  void rejectsInvalidScheduleAndLimitsDeliveryHistoryToTwentyRows() throws Exception {
    String invalidRequest = """
      {
        "enabled": false,
        "appId": "",
        "appSecret": "",
        "templateId": "",
        "costTemplateId": "",
        "openIds": "",
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
        "TEST_STATUS",
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

  private void configure(WechatNotificationProperties properties) {
    SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
    new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
    JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
    WechatNotificationSettingsRepository settingsRepository = new WechatNotificationSettingsRepository(
      jdbcTemplate,
      properties,
      new WechatSecretCipher(VALID_KEY)
    );
    WechatTemplateSender templateSender = new WechatTemplateSender() {
      @Override
      public void sendTemplate(
        org.ociarmmonitor.notification.WechatNotificationSettings settings,
        String openId,
        WechatTemplateType templateType,
        WechatTemplateMessage message
      ) {
        sentMessages++;
      }
    };
    DailyReportDataProvider dataProvider = new DailyReportDataProvider(
      new CloudInstanceRepository(jdbcTemplate),
      new MetricRepository(jdbcTemplate),
      new ServerStatusRepository(jdbcTemplate, 72),
      new ServerAlertService(new AlertRuleRepository(jdbcTemplate)),
      new SyncRunRepository(jdbcTemplate),
      new CostService(new CostRepository(jdbcTemplate), new ManualCostRepository(jdbcTemplate)),
      new TrafficService(new TrafficRepository(jdbcTemplate), new FreeQuotaRepository(jdbcTemplate))
    );
    PublicReportService publicReportService = new PublicReportService(
      jdbcTemplate,
      objectMapper,
      new PublicReportSnapshotMapper()
    );
    WechatNotificationService notificationService = new WechatNotificationService(
      settingsRepository,
      templateSender,
      dataProvider,
      publicReportService
    );
    deliveryLogRepository = new WechatDeliveryLogRepository(jdbcTemplate);
    WechatNotificationController controller = new WechatNotificationController(
      settingsRepository,
      notificationService,
      deliveryLogRepository
    );
    sentMessages = 0;
    mockMvc = MockMvcBuilders.standaloneSetup(controller)
      .setControllerAdvice(new GlobalExceptionHandler())
      .build();
  }

  private WechatNotificationProperties properties(boolean enabled, String costTemplateId) {
    return new WechatNotificationProperties(
      enabled,
      "wx_example_app_id",
      "wx_example_secret",
      "template_example_status",
      costTemplateId,
      "openid_example_1,openid_example_2",
      true,
      false,
      "09:00",
      "Asia/Shanghai",
      "https://api.weixin.qq.com"
    );
  }
}
