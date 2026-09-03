package com.shangan.planning.api;

import com.shangan.common.auth.CurrentUser;
import com.shangan.planning.application.BattleOrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 今日作战单 API 只暴露读取和完整快照保存，避免多个逐项写入口产生规则漂移。 */
@RestController
@RequestMapping("/api/v1/plans/{date}")
public class DailyPlanController {
  private final BattleOrderService battleOrders;

  public DailyPlanController(BattleOrderService battleOrders) {
    this.battleOrders = battleOrders;
  }

  @GetMapping
  BattleOrderService.PlanView plan(CurrentUser user, @PathVariable LocalDate date) {
    return battleOrders.get(user.userId(), date);
  }

  @PutMapping
  BattleOrderService.PlanView save(
      CurrentUser user,
      @PathVariable LocalDate date,
      @Valid @RequestBody SaveBattleOrderRequest request) {
    return battleOrders.save(user.userId(), user.timezone(), date, request.toCommand());
  }

  record SaveBattleOrderRequest(
      @Min(0) long expectedVersion, @NotNull List<@Valid BattleOrderItemRequest> items) {
    BattleOrderService.SaveCommand toCommand() {
      return new BattleOrderService.SaveCommand(
          expectedVersion, items.stream().map(BattleOrderItemRequest::toCommand).toList());
    }
  }

  record BattleOrderItemRequest(
      String existingItemId,
      @NotBlank String itemType,
      String mediaItemId,
      String mockExamPresetId,
      int sortOrder) {
    BattleOrderService.ItemCommand toCommand() {
      return new BattleOrderService.ItemCommand(
          existingItemId, itemType, mediaItemId, mockExamPresetId, sortOrder);
    }
  }
}
