package com.shangan.admin;

import com.shangan.upgrade.UpgradeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

/** 升级管理沿用 ADMIN Session 和 CSRF，客户端不得绕过后台提交任务。 */
@RestController
@RequestMapping("/admin/api/upgrades")
public class UpgradeAdminController {
  private final UpgradeService upgrades;

  public UpgradeAdminController(UpgradeService upgrades) {
    this.upgrades = upgrades;
  }

  /** 仅返回版本、进度、设置和安全错误。 */
  @GetMapping
  public Map<String, Object> status() {
    return upgrades.status();
  }

  /** 异步任务持久化后返回，不等待容器停止或重建。 */
  @PostMapping("/actions")
  public Map<String, Object> submit(@Valid @RequestBody Action request) {
    upgrades.submit(request.action());
    return Map.of("accepted", true);
  }

  /** 自动升级默认关闭，保存时显式校验时间与时区。 */
  @PostMapping("/config")
  public Map<String, Object> configure(@Valid @RequestBody Settings request) {
    upgrades.configure(request.automatic(), request.time(), request.timezone());
    return upgrades.status();
  }

  public record Action(@NotBlank String action) {}

  public record Settings(
      @NotNull Boolean automatic, @NotBlank String time, @NotBlank String timezone) {}
}
