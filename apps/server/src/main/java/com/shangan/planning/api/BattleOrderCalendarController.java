package com.shangan.planning.api;

import com.shangan.common.auth.CurrentUser;
import com.shangan.planning.application.BattleOrderService;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 学习日历只读查询，与按日读写接口分离以免日期路径吞掉集合查询。 */
@RestController
@RequestMapping("/api/v1/plans")
public class BattleOrderCalendarController {
  private final BattleOrderService battleOrders;

  public BattleOrderCalendarController(BattleOrderService battleOrders) {
    this.battleOrders = battleOrders;
  }

  @GetMapping
  BattleOrderService.CalendarView calendar(
      CurrentUser user, @RequestParam LocalDate from, @RequestParam LocalDate to) {
    return battleOrders.calendar(user.userId(), from, to);
  }
}
