package com.shangan.admin;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** 验证管理后台使用独立 Session 认证、ADMIN 权限和 CSRF 防护。 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminSecurityTest {

  @TempDir static Path databaseDirectory;

  @Autowired MockMvc mockMvc;

  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:sqlite:" + databaseDirectory.resolve("admin-security.db"));
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
    registry.add("app.security.bootstrap-admin-username", () -> "admin");
    registry.add("app.security.bootstrap-admin-password", () -> "admin-test-password");
  }

  @Test
  void loginPageIsPublic() throws Exception {
    mockMvc.perform(get("/admin/login")).andExpect(status().isOk());
  }

  @Test
  void protectedAdminPageRedirectsAnonymousUserToLogin() throws Exception {
    mockMvc.perform(get("/admin/health")).andExpect(status().is3xxRedirection());
  }

  @Test
  void normalUserCannotEnterAdminArea() throws Exception {
    mockMvc
        .perform(get("/admin/health").with(user("alice").roles("USER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminLoginPostRequiresCsrfToken() throws Exception {
    mockMvc
        .perform(
            post("/admin/login")
                .param("username", "admin")
                .param("password", "admin-test-password"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post("/admin/login")
                .with(csrf())
                .param("username", "admin")
                .param("password", "admin-test-password"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/admin/health"));
  }
}
