package com.shangan.common.api;

import org.springframework.http.HttpStatus;

/** 携带稳定业务错误码和安全用户提示的异常。 */
public final class BusinessException extends RuntimeException {

  private final HttpStatus status;
  private final String errorCode;

  /**
   * 创建业务异常。
   *
   * @param status 对应 HTTP 状态
   * @param errorCode 稳定业务错误码
   * @param safeMessage 可直接返回客户端的安全中文提示
   */
  public BusinessException(HttpStatus status, String errorCode, String safeMessage) {
    super(safeMessage);
    this.status = status;
    this.errorCode = errorCode;
  }

  public HttpStatus status() {
    return status;
  }

  public String errorCode() {
    return errorCode;
  }
}
