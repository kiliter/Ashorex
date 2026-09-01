package com.shangan.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/** 覆盖 App 登录、禁用用户和 Refresh Token 单次轮换的完整认证流程。 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir static Path databaseDirectory;

  @Autowired MockMvc mockMvc;
  @Autowired JdbcClient jdbc;

  /** 为测试隔离数据库，并提供满足长度要求的固定 JWT Secret。 */
  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url", () -> "jdbc:sqlite:" + databaseDirectory.resolve("identity.db"));
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
    registry.add("app.security.bootstrap-admin-username", () -> "");
    registry.add("app.security.bootstrap-admin-password", () -> "");
  }

  @BeforeEach
  void setUpUsers() {
    jdbc.sql("delete from refresh_tokens").update();
    jdbc.sql("delete from users").update();
    insertUser("user-1", "alice", "correct-password", true);
    insertUser("user-2", "disabled", "correct-password", false);
    insertUser("admin-1", "admin", "correct-password", true, "ADMIN");
  }

  @Test
  void validPasswordReturnsAccessAndRefreshTokens() throws Exception {
    MvcResult login =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody("alice", "correct-password")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.refreshToken").isNotEmpty())
            .andExpect(jsonPath("$.expiresInSeconds").value(900))
            .andReturn();

    String accessToken =
        JSON.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    mockMvc
        .perform(get("/api/v1/me").header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("user-1"))
        .andExpect(jsonPath("$.username").value("alice"));
  }

  @Test
  void authenticatedUserCanReadAndUpdatePreferences() throws Exception {
    String accessToken = loginAndGetAccessToken();

    mockMvc
        .perform(get("/api/v1/preferences").header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.timezone").value("Asia/Shanghai"))
        .andExpect(jsonPath("$.aliveCheckEnabled").value(true))
        .andExpect(jsonPath("$.aliveCheckIntervalPercent").value(50))
        .andExpect(jsonPath("$.dayEndLocalTime").value("23:59"));

    mockMvc
        .perform(
            put("/api/v1/preferences")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    JSON.writeValueAsString(
                        Map.of(
                            "timezone",
                            "Asia/Shanghai",
                            "aliveCheckEnabled",
                            false,
                            "aliveCheckIntervalPercent",
                            35,
                            "dayEndLocalTime",
                            "22:30"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.aliveCheckEnabled").value(false))
        .andExpect(jsonPath("$.aliveCheckIntervalPercent").value(35))
        .andExpect(jsonPath("$.dayEndLocalTime").value("22:30"));
  }

  @Test
  void everyUserCanSetAliveCheckProgressPercent() throws Exception {
    String userToken = loginAndGetAccessToken("alice");
    String request =
        JSON.writeValueAsString(
            Map.of(
                "timezone",
                "Asia/Shanghai",
                "aliveCheckEnabled",
                true,
                "aliveCheckIntervalPercent",
                7,
                "dayEndLocalTime",
                "23:59"));

    mockMvc
        .perform(
            put("/api/v1/preferences")
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.aliveCheckIntervalPercent").value(7));

    assertThat(
            jdbc.sql("select alive_check_interval_percent from users where id = 'user-1'")
                .query(Integer.class)
                .single())
        .isEqualTo(7);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 51})
  void aliveCheckProgressPercentMustStayInsideSliderRange(int percent) throws Exception {
    String userToken = loginAndGetAccessToken("alice");

    mockMvc
        .perform(
            put("/api/v1/preferences")
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    JSON.writeValueAsString(
                        Map.of(
                            "timezone",
                            "Asia/Shanghai",
                            "aliveCheckEnabled",
                            true,
                            "aliveCheckIntervalPercent",
                            percent,
                            "dayEndLocalTime",
                            "23:59"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));

    assertThat(
            jdbc.sql("select alive_check_interval_percent from users where id = 'user-1'")
                .query(Integer.class)
                .single())
        .isEqualTo(50);
  }

  @Test
  void invalidPasswordReturnsStableUnauthorizedError() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("alice", "wrong-password")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_CREDENTIALS"));
  }

  @Test
  void disabledUserIsRejected() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("disabled", "correct-password")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("AUTH_USER_DISABLED"));
  }

  @Test
  void refreshTokenRotatesAndOldTokenCannotBeReused() throws Exception {
    MvcResult login =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody("alice", "correct-password")))
            .andExpect(status().isOk())
            .andReturn();
    String originalRefreshToken =
        JSON.readTree(login.getResponse().getContentAsString()).get("refreshToken").asText();

    MvcResult refreshed =
        mockMvc
            .perform(
                post("/api/v1/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(refreshBody(originalRefreshToken)))
            .andExpect(status().isOk())
            .andReturn();
    String rotatedRefreshToken =
        JSON.readTree(refreshed.getResponse().getContentAsString()).get("refreshToken").asText();

    assertThat(rotatedRefreshToken).isNotEqualTo(originalRefreshToken);
    mockMvc
        .perform(
            post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(refreshBody(originalRefreshToken)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value("AUTH_REFRESH_TOKEN_INVALID"));
  }

  private void insertUser(String id, String username, String rawPassword, boolean enabled) {
    insertUser(id, username, rawPassword, enabled, "USER");
  }

  private void insertUser(
      String id, String username, String rawPassword, boolean enabled, String role) {
    String passwordHash = new BCryptPasswordEncoder(12).encode(rawPassword);
    jdbc.sql(
            """
                        insert into users (
                            id, username, password_hash, display_name, role, timezone,
                            alive_check_level, day_end_local_time, enabled, created_at, updated_at
                        ) values (
                            :id, :username, :passwordHash, :displayName, :role, 'Asia/Shanghai',
                            'NORMAL', '23:59', :enabled, 1, 1
                        )
                        """)
        .params(
            Map.of(
                "id", id,
                "username", username,
                "passwordHash", passwordHash,
                "displayName", username,
                "role", role,
                "enabled", enabled ? 1 : 0))
        .update();
  }

  private String loginBody(String username, String password) throws Exception {
    return JSON.writeValueAsString(Map.of("username", username, "password", password));
  }

  private String refreshBody(String refreshToken) throws Exception {
    return JSON.writeValueAsString(Map.of("refreshToken", refreshToken));
  }

  private String loginAndGetAccessToken() throws Exception {
    return loginAndGetAccessToken("alice");
  }

  private String loginAndGetAccessToken(String username) throws Exception {
    MvcResult login =
        mockMvc
            .perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody(username, "correct-password")))
            .andExpect(status().isOk())
            .andReturn();
    return JSON.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
  }
}
