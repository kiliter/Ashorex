package com.shangan.ai.infrastructure;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** MCP 安全边界：固定 URL、显式名称白名单和响应硬上限在模型看到内容前生效。 */
@Component
public class McpToolAllowlist {
  private static final Set<String> FORBIDDEN_FRAGMENTS =
      Set.of("filesystem", "shell", "exec", "email", "calendar", "database", "write", "delete");

  public Set<String> parse(String configured) {
    Set<String> result = new LinkedHashSet<>();
    if (configured == null || configured.isBlank()) return Set.of();
    Arrays.stream(configured.split(","))
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .filter(this::isSearchOnlyName)
        .forEach(result::add);
    return Set.copyOf(result);
  }

  public List<String> filter(List<String> discovered, Set<String> allowed) {
    return discovered.stream().filter(allowed::contains).filter(this::isSearchOnlyName).toList();
  }

  public boolean isAllowed(String name, Set<String> allowed) {
    return allowed.contains(name) && isSearchOnlyName(name);
  }

  public String truncate(String response, int maximumCharacters) {
    if (response == null) return "";
    int limit = Math.max(0, maximumCharacters);
    if (response.length() <= limit) return response;
    return response.substring(0, limit) + "\n[联网工具响应已按安全上限截断]";
  }

  public URI requireFixedHttpUrl(String configuredUrl) {
    URI uri = URI.create(configuredUrl);
    boolean validScheme =
        "https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme());
    if (!validScheme
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getFragment() != null) {
      throw new IllegalArgumentException("MCP URL 必须是固定的 HTTP(S) 地址");
    }
    return uri;
  }

  private boolean isSearchOnlyName(String name) {
    String normalized = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
    return (normalized.contains("search") || normalized.contains("extract"))
        && FORBIDDEN_FRAGMENTS.stream().noneMatch(normalized::contains);
  }
}
