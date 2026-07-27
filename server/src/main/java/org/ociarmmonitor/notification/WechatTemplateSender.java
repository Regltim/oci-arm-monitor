package org.ociarmmonitor.notification;

public interface WechatTemplateSender {

  void sendTemplate(
    WechatNotificationSettings settings,
    String openId,
    WechatTemplateMessage message
  );
}
