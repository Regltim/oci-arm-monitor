package org.ociarmmonitor.notification;

public record WechatTemplateMessage(
  String first,
  String level,
  String metric,
  String status,
  String content,
  String time,
  String remark
) {
}
