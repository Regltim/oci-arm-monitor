package org.ociarmmonitor.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import jakarta.servlet.http.HttpServletResponse;
import org.ociarmmonitor.publicreport.PublicReportNotFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(MethodArgumentNotValidException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ApiResponse<Void> handleValidation(MethodArgumentNotValidException exception) {
    FieldError fieldError = exception.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
    String message = fieldError == null ? "请求参数不合法" : fieldError.getDefaultMessage();
    LOGGER.warn("Request validation failed: {}", message);
    return ApiResponse.fail(message);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException exception) {
    LOGGER.warn("Bad request: {}", exception.getMessage());
    return ApiResponse.fail(exception.getMessage());
  }

  @ExceptionHandler(NoResourceFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public ApiResponse<Void> handleNoResource(NoResourceFoundException exception) {
    LOGGER.warn("Resource not found: {}", exception.getResourcePath());
    return ApiResponse.fail("接口不存在或静态资源不存在");
  }

  @ExceptionHandler(PublicReportNotFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public ApiResponse<Void> handlePublicReportNotFound(
    PublicReportNotFoundException exception,
    HttpServletResponse response
  ) {
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("X-Robots-Tag", "noindex");
    response.setHeader("Referrer-Policy", "no-referrer");
    return ApiResponse.fail("报告不存在或已过期");
  }

  @ExceptionHandler(Exception.class)
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public ApiResponse<Void> handleException(Exception exception) {
    LOGGER.error("Unhandled server exception", exception);
    return ApiResponse.fail("服务暂时不可用，请稍后重试");
  }
}
