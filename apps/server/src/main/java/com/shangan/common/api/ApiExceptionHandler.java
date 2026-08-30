package com.shangan.common.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 将可预期错误转换为 RFC Problem Details，并避免向客户端泄露堆栈。 */
@RestControllerAdvice
public class ApiExceptionHandler {

  /** 返回带稳定 errorCode 的业务错误。 */
  @ExceptionHandler(BusinessException.class)
  ProblemDetail handleBusinessException(BusinessException exception, HttpServletRequest request) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
    problem.setTitle("业务请求失败");
    problem.setProperty("errorCode", exception.errorCode());
    problem.setProperty("requestId", requestId(request));
    return problem;
  }

  /** 将 Bean Validation 错误转换为可操作且不含内部实现细节的响应。 */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  ProblemDetail handleValidationException(
      MethodArgumentNotValidException exception, HttpServletRequest request) {
    String detail =
        Optional.ofNullable(exception.getBindingResult().getFieldError())
            .map(FieldError::getDefaultMessage)
            .orElse("请求参数不合法");
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
    problem.setTitle("参数校验失败");
    problem.setProperty("errorCode", "VALIDATION_FAILED");
    problem.setProperty("requestId", requestId(request));
    return problem;
  }

  /** 优先复用过滤器生成的请求 ID；测试切片或特殊调用缺失时使用安全兜底值。 */
  private String requestId(HttpServletRequest request) {
    Object requestId = request.getAttribute(RequestIdFilter.ATTRIBUTE);
    if (requestId instanceof String value && !value.isBlank()) return value;
    String upstream = request.getHeader(RequestIdFilter.HEADER);
    return upstream == null || upstream.isBlank()
        ? java.util.UUID.randomUUID().toString()
        : upstream;
  }
}
