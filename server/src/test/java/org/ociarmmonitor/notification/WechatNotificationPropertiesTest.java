package org.ociarmmonitor.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class WechatNotificationPropertiesTest {

  @Test
  void springCreatesPropertiesFromTheValueInjectedConstructor() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("wechat-test", Map.of(
        "monitor.wechat.enabled", "true",
        "monitor.wechat.app-id", "wx_example_app",
        "monitor.wechat.app-secret", "example-secret",
        "monitor.wechat.template-id", "template_example_status",
        "monitor.wechat.cost-template-id", "template_example_cost",
        "monitor.wechat.open-ids", "openid_example_1",
        "monitor.wechat.daily-summary-enabled", "true"
      )));
      context.register(WechatNotificationProperties.class);

      context.refresh();

      WechatNotificationProperties properties = context.getBean(WechatNotificationProperties.class);
      assertThat(properties.enabled()).isTrue();
      assertThat(properties.templateId()).isEqualTo("template_example_status");
      assertThat(properties.costTemplateId()).isEqualTo("template_example_cost");
    }
  }
}
