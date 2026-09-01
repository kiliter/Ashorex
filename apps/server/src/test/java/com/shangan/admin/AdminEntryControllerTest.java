package com.shangan.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 验证浏览器常用入口不会暴露默认 Error Page。 */
class AdminEntryControllerTest {

  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(new AdminEntryController()).build();

  @Test
  void rootAndAdminEntryRedirectToAdminHealth() throws Exception {
    mockMvc
        .perform(get("/"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/admin/health"));

    mockMvc
        .perform(get("/admin"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/admin/health"));
  }
}
