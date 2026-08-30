package com.shangan.common.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.UUID;
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

  /** 优先复用上游请求 ID；当前尚无过滤器时生成安全的临时请求 ID。 */
  private String requestId(HttpServletRequest request) {
    String requestId = request.getHeader("X-Request-ID");
    return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
  }
}
