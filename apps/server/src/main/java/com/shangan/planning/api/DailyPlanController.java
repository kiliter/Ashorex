package com.shangan.planning.api;

import com.shangan.common.auth.CurrentUser;
import com.shangan.planning.application.DailyPlanService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.LocalDate;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 每日计划编辑、锁定、开摆与还债任务 API。 */
@RestController
@RequestMapping("/api/v1/plans/{date}")
public class DailyPlanController {
  private final DailyPlanService plans;

  public DailyPlanController(DailyPlanService plans) {
    this.plans = plans;
  }

  @GetMapping
  DailyPlanService.PlanView plan(CurrentUser user, @PathVariable LocalDate date) {
    return plans.getOrCreate(user.userId(), date);
  }

  @PostMapping("/items")
  DailyPlanService.PlanView add(
      CurrentUser user, @PathVariable LocalDate date, @Valid @RequestBody ItemRequest request) {
    return plans.addItem(user.userId(), date, request.toDraft());
  }

  @PutMapping("/items/{itemId}")
  DailyPlanService.PlanView update(
      CurrentUser user,
      @PathVariable LocalDate date,
      @PathVariable String itemId,
      @Valid @RequestBody UpdateItemRequest request) {
    return plans.updateItem(
        user.userId(),
        date,
        itemId,
        request.title(),
        request.plannedSeconds(),
        request.sortOrder());
  }

  @DeleteMapping("/items/{itemId}")
  DailyPlanService.PlanView delete(
      CurrentUser user, @PathVariable LocalDate date, @PathVariable String itemId) {
    return plans.deleteItem(user.userId(), date, itemId);
  }

  @PostMapping("/lock")
  DailyPlanService.PlanView lock(CurrentUser user, @PathVariable LocalDate date) {
    return plans.lock(user.userId(), date);
  }

  @GetMapping("/abandon-preview")
  DailyPlanService.AbandonPreview preview(CurrentUser user, @PathVariable LocalDate date) {
    return plans.previewAbandon(user.userId(), date);
  }

  @PostMapping("/abandon")
  DailyPlanService.PlanView abandon(
      CurrentUser user, @PathVariable LocalDate date, @Valid @RequestBody AbandonRequest request) {
    return plans.abandon(
        user.userId(),
        date,
        request.reasonCode(),
        request.reasonText() == null ? "" : request.reasonText());
  }

  @PostMapping("/debt-items")
  DailyPlanService.PlanView addDebtItems(
      CurrentUser user,
      @PathVariable LocalDate date,
      @Valid @RequestBody DebtItemsRequest request) {
    return plans.addDebtItems(user.userId(), date, request.debtIds());
  }

  record ItemRequest(
      @NotBlank String itemType,
      String title,
      String mediaItemId,
      String debtId,
      @Min(1) long plannedSeconds,
      int sortOrder) {
    DailyPlanService.ItemDraft toDraft() {
      return new DailyPlanService.ItemDraft(
          itemType, title == null ? "" : title, mediaItemId, debtId, plannedSeconds, sortOrder);
    }
  }

  record UpdateItemRequest(@NotBlank String title, @Min(1) long plannedSeconds, int sortOrder) {}

  record AbandonRequest(@NotBlank String reasonCode, String reasonText) {}

  record DebtItemsRequest(@NotEmpty List<@NotBlank String> debtIds) {}
}
