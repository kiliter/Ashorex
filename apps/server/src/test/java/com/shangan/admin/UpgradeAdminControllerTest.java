package com.shangan.admin;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.shangan.common.api.ApiExceptionHandler;
import com.shangan.upgrade.UpgradeService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Controller 验证缺失开关不能误开启升级，合法请求只进入应用服务。 */
class UpgradeAdminControllerTest {
  @Test
  void 校验输入并异步提交() throws Exception {
    var service = mock(UpgradeService.class);
    var mvc =
        MockMvcBuilders.standaloneSetup(new UpgradeAdminController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    mvc.perform(
            post("/admin/api/upgrades/actions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"APPLY\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accepted").value(true));
    verify(service).submit("APPLY");
    mvc.perform(
            post("/admin/api/upgrades/config")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"time\":\"03:00\",\"timezone\":\"Asia/Shanghai\"}"))
        .andExpect(status().isBadRequest());
    verifyNoMoreInteractions(service);
  }
}
