package com.shangan.debt.api;

import com.shangan.common.auth.CurrentUser;
import com.shangan.debt.application.DebtService;
import com.shangan.debt.domain.LearningDebt;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 当前用户欠债只读列表；客户端不能直接核销。 */
@RestController
@RequestMapping("/api/v1/debts")
public class DebtController {
  private final DebtService debts;

  public DebtController(DebtService debts) {
    this.debts = debts;
  }

  @GetMapping
  List<LearningDebt> list(
      CurrentUser user,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    List<LearningDebt> all = debts.openDebts(user.userId());
    int pageSize = Math.min(100, Math.max(1, size));
    int from = Math.min(Math.max(0, page) * pageSize, all.size());
    return all.subList(from, Math.min(all.size(), from + pageSize));
  }
}
