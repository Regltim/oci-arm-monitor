package org.ociarmmonitor.publicreport;

public class PublicReportNotFoundException extends RuntimeException {

  public PublicReportNotFoundException() {
    super("报告不存在或已过期");
  }
}
