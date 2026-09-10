package com.shangan.appupdate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shangan.common.api.BusinessException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 固定仓库 GitHub 客户端；元数据短缓存，安装包始终流式传输且每次读取有超时。 */
@Component
public class GithubReleaseClient {
  private final ObjectMapper mapper;
  private final Clock clock;
  private JsonNode cached;
  private Instant expires = Instant.EPOCH;

  public GithubReleaseClient(ObjectMapper mapper, Clock clock) {
    this.mapper = mapper;
    this.clock = clock;
  }

  /** 合并并发检查；只缓存成功结果，过期后的错误不能伪装成最新版本。 */
  public synchronized JsonNode latest() {
    if (cached != null && clock.instant().isBefore(expires)) return cached;
    cached = metadata("latest");
    expires = clock.instant().plus(Duration.ofMinutes(5));
    return cached;
  }

  /** 固定版本下载重新确认正式 Release，避免参数指向未发布附件。 */
  public JsonNode release(String tag) {
    return metadata("tags/" + AppRelease.tag(tag));
  }

  private JsonNode metadata(String suffix) {
    HttpURLConnection connection = null;
    try {
      connection =
          open(
              URI.create("https://api.github.com/repos/kiliter/Ashorex/releases/" + suffix),
              null,
              null);
      if (connection.getResponseCode() == 404)
        throw new BusinessException(HttpStatus.NOT_FOUND, "APP_UPDATE_NOT_FOUND", "暂无可用的正式版本");
      if (connection.getResponseCode() != 200) throw unavailable();
      try (InputStream stream = connection.getInputStream()) {
        byte[] bytes = stream.readNBytes(2 * 1024 * 1024 + 1);
        if (bytes.length > 2 * 1024 * 1024) throw unavailable();
        return mapper.readTree(bytes);
      }
    } catch (IOException e) {
      throw unavailable();
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  /** 重定向每一跳均验证目标；无凭据请求，不向 GitHub CDN 传 App 登录信息。 */
  HttpURLConnection open(URI uri, String range, String ifRange) throws IOException {
    for (int redirects = 0; redirects < 5; redirects++) {
      validateTarget(uri);
      HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
      connection.setConnectTimeout(10000);
      connection.setReadTimeout(30000);
      connection.setInstanceFollowRedirects(false);
      connection.setRequestProperty("User-Agent", "Ashorex-App-Update");
      connection.setRequestProperty("Accept-Encoding", "identity");
      if (range != null) connection.setRequestProperty("Range", range);
      if (ifRange != null) connection.setRequestProperty("If-Range", ifRange);
      int status;
      try {
        status = connection.getResponseCode();
      } catch (IOException e) {
        connection.disconnect();
        throw e;
      }
      if (Set.of(301, 302, 303, 307, 308).contains(status)) {
        String location = connection.getHeaderField("Location");
        connection.disconnect();
        if (location == null) throw unavailable();
        uri = uri.resolve(location);
      } else return connection;
    }
    throw unavailable();
  }

  /** 下载允许的主机是精确列表，不接受任意子域、用户信息或非 HTTPS 端口。 */
  static void validateTarget(URI uri) {
    if (!"https".equals(uri.getScheme())
        || uri.getUserInfo() != null
        || uri.getHost() == null
        || (uri.getPort() != -1 && uri.getPort() != 443)
        || !Set.of(
                "api.github.com",
                "github.com",
                "release-assets.githubusercontent.com",
                "objects.githubusercontent.com")
            .contains(uri.getHost()))
      throw new BusinessException(HttpStatus.BAD_GATEWAY, "APP_UPDATE_SOURCE_INVALID", "安装包下载来源无效");
  }

  static BusinessException unavailable() {
    return new BusinessException(
        HttpStatus.SERVICE_UNAVAILABLE, "APP_UPDATE_UNAVAILABLE", "暂时无法查询或下载更新，请稍后重试");
  }
}
