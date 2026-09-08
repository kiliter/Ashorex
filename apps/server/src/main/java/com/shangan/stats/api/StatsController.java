package com.shangan.stats.api;

import com.shangan.common.auth.CurrentUser;
import com.shangan.stats.application.StatsService;
import com.shangan.stats.application.StatsService.StatsView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 数据 Tab 的统计 API。 */
@RestController
@RequestMapping("/api/v1/stats")
public class StatsController {

  private final StatsService stats;

  public StatsController(StatsService stats) {
    this.stats = stats;
  }

  @GetMapping
  StatsView stats(
      CurrentUser currentUser,
      @RequestParam(defaultValue = "DAY") String range,
      @RequestParam(required = false) String date) {
    return stats.stats(currentUser.userId(), range, date);
  }
}
