package com.shangan.admin;

import com.shangan.nag.application.NagTransportService;
import com.shangan.nag.domain.NagTransportMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

/** 催办策略页的全局传输切换，沿用后台 Session、ADMIN 和 CSRF 校验。 */
@RestController
@RequestMapping("/admin/api/nag-transport")
public class NagTransportAdminController {
  private final NagTransportService transport;

  public NagTransportAdminController(NagTransportService transport) {
    this.transport = transport;
  }

  @GetMapping
  public ModeView current() {
    return new ModeView(transport.current());
  }

  @PostMapping
  public ModeView save(@Valid @RequestBody ModeView request) {
    transport.save(request.mode());
    return new ModeView(transport.current());
  }

  /** 未知枚举按明确的业务错误返回，避免默认解析错误暴露内部类型。 */
  @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
  public org.springframework.http.ProblemDetail invalidMode() {
    var problem =
        org.springframework.http.ProblemDetail.forStatusAndDetail(
            org.springframework.http.HttpStatus.BAD_REQUEST, "通知方式只能选择 SSE 或 HEARTBEAT");
    problem.setProperty("errorCode", "NAG_TRANSPORT_INVALID");
    return problem;
  }

  /** 只接受两个确定模式，不允许空值悄悄重置配置。 */
  public record ModeView(@NotNull NagTransportMode mode) {}
}
