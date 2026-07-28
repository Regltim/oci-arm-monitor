package org.ociarmmonitor.notification;

public interface WechatTemplateSender {

  void sendTemplate(
    WechatNotificationSettings settings,
    String openId,
    WechatTemplateType templateType,
    WechatTemplateMessage message
  );
}
