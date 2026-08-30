package com.shangan.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shangan.common.api.ApiExceptionHandler;
import com.shangan.common.api.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 验证所有业务错误都返回稳定 errorCode，而不是堆栈或内部异常。 */
@WebMvcTest
@AutoConfigureMockMvc(addFilters = false)
@Import({ApiExceptionHandler.class, ProblemDetailTest.TestController.class})
class ProblemDetailTest {

  @Autowired MockMvc mockMvc;

  @Test
  void returnsStableBusinessErrorCode() throws Exception {
    mockMvc
        .perform(get("/test/locked-plan").header("X-Request-ID", "request-1"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errorCode").value("PLAN_ALREADY_LOCKED"))
        .andExpect(jsonPath("$.requestId").value("request-1"))
        .andExpect(jsonPath("$.detail").value("计划已锁定"));
  }

  /** 只用于触发异常映射的测试控制器。 */
  @RestController
  public static class TestController {

    @GetMapping("/test/locked-plan")
    public void lockedPlan() {
      throw new BusinessException(HttpStatus.CONFLICT, "PLAN_ALREADY_LOCKED", "计划已锁定");
    }
  }
}
