package org.ociarmmonitor.notification;

public interface WechatTemplateSender {

  void sendTemplate(
    WechatNotificationSettings settings,
    String openId,
    WechatTemplateType templateType,
    WechatTemplateMessage message
  );

  default void sendTemplate(
    WechatNotificationSettings settings,
    String openId,
    WechatTemplateType templateType,
    WechatTemplateMessage message,
    String detailUrl
  ) {
    sendTemplate(settings, openId, templateType, message);
  }
}
