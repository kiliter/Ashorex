package com.shangan.upgrade;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 无敏感字段的构建探测，供 updater 在健康通过后确认实际运行版本。 */
@RestController
public class UpgradeReadinessController {
  private final UpgradeService upgrades;

  public UpgradeReadinessController(UpgradeService upgrades) {
    this.upgrades = upgrades;
  }

  @GetMapping("/internal/upgrade-readiness")
  public Map<String, Object> readiness() {
    return Map.of("version", upgrades.version(), "maintenance", upgrades.maintenance());
  }
}
