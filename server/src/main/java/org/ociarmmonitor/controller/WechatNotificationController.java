package org.ociarmmonitor.controller;

import java.util.List;
import org.ociarmmonitor.common.ApiResponse;
import org.ociarmmonitor.notification.WechatDeliveryLogRepository;
import org.ociarmmonitor.notification.WechatDeliveryResult;
import org.ociarmmonitor.notification.WechatNotificationService;
import org.ociarmmonitor.notification.WechatNotificationSettingsRepository;
import org.ociarmmonitor.notification.WechatNotificationSettingsStatus;
import org.ociarmmonitor.notification.WechatNotificationSettingsUpdateRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/settings/wechat")
public class WechatNotificationController {

  private final WechatNotificationSettingsRepository settingsRepository;
  private final WechatNotificationService notificationService;
  private final WechatDeliveryLogRepository deliveryLogRepository;

  public WechatNotificationController(
    WechatNotificationSettingsRepository settingsRepository,
    WechatNotificationService notificationService,
    WechatDeliveryLogRepository deliveryLogRepository
  ) {
    this.settingsRepository = settingsRepository;
    this.notificationService = notificationService;
    this.deliveryLogRepository = deliveryLogRepository;
  }

  @GetMapping
  public ApiResponse<WechatNotificationSettingsStatus> getSettings() {
    return ApiResponse.ok(settingsRepository.status());
  }

  @PutMapping
  public ApiResponse<WechatNotificationSettingsStatus> updateSettings(
    @RequestBody WechatNotificationSettingsUpdateRequest request
  ) {
    settingsRepository.update(request);
    return ApiResponse.ok(settingsRepository.status(), "微信公众号通知配置已保存");
  }

  @PostMapping("/test")
  public ApiResponse<WechatDeliveryResult> sendTest() {
    WechatDeliveryResult result = notificationService.sendTest();
    deliveryLogRepository.save(result);
    return ApiResponse.ok(result, result.message());
  }

  @GetMapping("/deliveries")
  public ApiResponse<List<WechatDeliveryResult>> listDeliveries() {
    return ApiResponse.ok(deliveryLogRepository.listRecent(20));
  }
}
