package com.shangan.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shangan.ai.infrastructure.McpToolAllowlist;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证 MCP 仅暴露显式搜索工具，并在模型看到响应前执行长度限制。 */
class McpToolAllowlistTest {
  private final McpToolAllowlist allowlist = new McpToolAllowlist();

  @Test
  void filtersDiscoveredToolsByExplicitSearchOnlyAllowlist() {
    var allowed = allowlist.parse("web_search,web_extract,filesystem_write");

    assertThat(
            allowlist.filter(
                List.of("web_search", "web_extract", "filesystem_write", "shell_exec"), allowed))
        .containsExactly("web_search", "web_extract");
  }

  @Test
  void validatesFixedUrlAndTruncatesOversizedResult() {
    assertThat(allowlist.requireFixedHttpUrl("https://search.example/mcp").getHost())
        .isEqualTo("search.example");
    assertThatThrownBy(() -> allowlist.requireFixedHttpUrl("file:///tmp/mcp"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(allowlist.truncate("123456", 5)).startsWith("12345").contains("已按安全上限截断");
  }
}
