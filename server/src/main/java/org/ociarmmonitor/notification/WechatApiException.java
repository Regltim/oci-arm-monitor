package org.ociarmmonitor.notification;

public class WechatApiException extends RuntimeException {

  private final Integer errorCode;

  public WechatApiException(String message) {
    this(message, null, null);
  }

  public WechatApiException(String message, Throwable cause) {
    this(message, null, cause);
  }

  public WechatApiException(String message, Integer errorCode) {
    this(message, errorCode, null);
  }

  private WechatApiException(String message, Integer errorCode, Throwable cause) {
    super(message, cause);
    this.errorCode = errorCode;
  }

  public Integer errorCode() {
    return errorCode;
  }
}
