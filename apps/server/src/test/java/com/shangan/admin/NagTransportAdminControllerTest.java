package com.shangan.admin;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.shangan.common.api.ApiExceptionHandler;
import com.shangan.nag.application.NagTransportService;
import com.shangan.nag.domain.NagTransportMode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 通知方式配置的输入边界：空值与未知模式都不能落库。 */
class NagTransportAdminControllerTest {
  @Test
  void 模式保存及非法输入() throws Exception {
    var service = mock(NagTransportService.class);
    when(service.current()).thenReturn(NagTransportMode.HEARTBEAT);
    var mvc =
        MockMvcBuilders.standaloneSetup(new NagTransportAdminController(service))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    mvc.perform(
            post("/admin/api/nag-transport")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"HEARTBEAT\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("HEARTBEAT"));
    verify(service).save(NagTransportMode.HEARTBEAT);
    mvc.perform(
            post("/admin/api/nag-transport")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"mode\":\"INVALID\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("NAG_TRANSPORT_INVALID"));
    mvc.perform(
            post("/admin/api/nag-transport").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    verify(service).current();
    verifyNoMoreInteractions(service);
  }
}
