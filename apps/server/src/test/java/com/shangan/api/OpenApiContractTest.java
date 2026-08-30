package com.shangan.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** 将 Springdoc 实际生成结果与仓库中的冻结合同逐字比较，阻止 API 漂移进入 CI。 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiContractTest {
  @TempDir static Path databaseDirectory;

  @Autowired MockMvc mockMvc;

  @DynamicPropertySource
  static void configureApplication(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> "jdbc:sqlite:" + databaseDirectory.resolve("openapi-contract.db"));
    registry.add("app.security.jwt-secret", () -> "test-jwt-secret-with-at-least-thirty-two-bytes");
  }

  @Test
  void generatedContractMatchesCommittedYaml() throws Exception {
    String generated =
        new String(
                mockMvc
                    .perform(get("/v3/api-docs.yaml"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsByteArray(),
                StandardCharsets.UTF_8)
            .replace("\r\n", "\n");

    Path generatedArtifact = Path.of("target", "openapi.generated.yaml");
    Files.writeString(generatedArtifact, generated, StandardCharsets.UTF_8);
    Path committed = Path.of("..", "..", "docs", "api", "openapi.yaml").normalize();

    assertThat(committed).as("冻结合同不存在；生成结果已写入 %s", generatedArtifact).exists();
    assertThat(Files.readString(committed, StandardCharsets.UTF_8).replace("\r\n", "\n"))
        .as("OpenAPI 已漂移；请审查 target/openapi.generated.yaml 后更新冻结合同")
        .isEqualTo(generated);
    assertThat(generated).doesNotContain("/ios");
    assertThat(generated)
        .contains("bearerAuth:")
        .doesNotContain("#/components/schemas/CurrentUser");
  }
}
