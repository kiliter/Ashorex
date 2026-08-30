package com.shangan.media.emby;

import com.shangan.common.api.BusinessException;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 只访问固定 Emby 源站的流式代理，并负责 HLS 子资源重写。 */
@Component
public class EmbyStreamProxy {
  private static final Pattern URI_ATTRIBUTE = Pattern.compile("URI=\"([^\"]+)\"");
  private static final List<String> SAFE_RESPONSE_HEADERS =
      List.of("Content-Range", "Accept-Ranges", "Content-Length", "Content-Type", "Cache-Control");

  private final EmbyProperties properties;
  private final HttpClient http;

  public EmbyStreamProxy(EmbyProperties properties) {
    this.properties = properties;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  }

  /** 打开上游流；调用方必须关闭结果，视频与分片不会被整体缓冲。 */
  public ProxyResponse open(String pathAndQuery, String range, String ifRange) {
    RuntimeIntegrationSettings.Emby configuration = properties.current();
    if (!configuration.configured()) throw unavailable();
    URI baseUri = fixedBaseUri(configuration.baseUrl());
    URI target = resolveTarget(pathAndQuery, baseUri);
    try {
      HttpRequest.Builder request =
          HttpRequest.newBuilder(target)
              .timeout(Duration.ofSeconds(30))
              .header("X-Emby-Token", configuration.apiKey())
              .GET();
      if (range != null && !range.isBlank()) request.header("Range", range);
      if (ifRange != null && !ifRange.isBlank()) request.header("If-Range", ifRange);
      HttpResponse<InputStream> response =
          http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
      HttpHeaders headers = new HttpHeaders();
      for (String name : SAFE_RESPONSE_HEADERS) {
        response.headers().firstValue(name).ifPresent(value -> headers.set(name, value));
      }
      return new ProxyResponse(response.statusCode(), headers, response.body(), target);
    } catch (Exception exception) {
      throw new BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE, "EMBY_STREAM_UNAVAILABLE", "媒体流暂时不可用");
    }
  }

  /** 将 HLS 相对或同源绝对 URI 改写为同一票据下的代理地址。 */
  public String rewriteManifest(String ticket, String manifest, URI manifestUri) {
    StringBuilder result = new StringBuilder();
    for (String line : manifest.split("\\R", -1)) {
      if (line.startsWith("#") && line.contains("URI=\"")) {
        result.append(rewriteUriAttributes(ticket, line, manifestUri));
      } else if (line.isBlank() || line.startsWith("#")) {
        result.append(line);
      } else {
        result.append(proxyUrl(ticket, line, manifestUri));
      }
      result.append('\n');
    }
    return result.toString();
  }

  /** 重写 EXT-X-MAP、EXT-X-KEY 等指令中的 URI 属性，避免初始化分片绕过代理。 */
  private String rewriteUriAttributes(String ticket, String line, URI manifestUri) {
    Matcher matcher = URI_ATTRIBUTE.matcher(line);
    StringBuilder rewritten = new StringBuilder();
    while (matcher.find()) {
      matcher.appendReplacement(
          rewritten,
          Matcher.quoteReplacement(
              "URI=\"" + proxyUrl(ticket, matcher.group(1), manifestUri) + "\""));
    }
    matcher.appendTail(rewritten);
    return rewritten.toString();
  }

  private String proxyUrl(String ticket, String reference, URI manifestUri) {
    rejectTraversal(reference);
    URI resolved = manifestUri.resolve(reference).normalize();
    verifySameOrigin(resolved, manifestUri);
    StringBuilder value =
        new StringBuilder("/api/v1/playback/")
            .append(ticket)
            .append("/proxy")
            .append(resolved.getRawPath());
    if (resolved.getRawQuery() != null) value.append('?').append(resolved.getRawQuery());
    return value.toString();
  }

  private URI resolveTarget(String pathAndQuery, URI baseUri) {
    if (pathAndQuery == null || !pathAndQuery.startsWith("/")) throw forbidden();
    rejectTraversal(pathAndQuery);
    URI target = baseUri.resolve(pathAndQuery).normalize();
    verifySameOrigin(target, baseUri);
    return target;
  }

  /** 同时拒绝明文和百分号编码的点、斜杠，阻断上游服务器二次解码后的路径穿越。 */
  private void rejectTraversal(String value) {
    String normalized = value.toLowerCase(java.util.Locale.ROOT);
    if (normalized.contains("..")
        || normalized.contains("%2e")
        || normalized.contains("%2f")
        || normalized.contains("%5c")) {
      throw forbidden();
    }
  }

  private void verifySameOrigin(URI target, URI allowedOrigin) {
    if (!allowedOrigin.getScheme().equalsIgnoreCase(target.getScheme())
        || !allowedOrigin.getHost().equalsIgnoreCase(target.getHost())
        || effectivePort(allowedOrigin) != effectivePort(target)) {
      throw forbidden();
    }
  }

  private URI fixedBaseUri(String value) {
    try {
      URI baseUri = URI.create(value);
      if (!List.of("http", "https").contains(baseUri.getScheme())
          || baseUri.getHost() == null
          || baseUri.getUserInfo() != null) {
        throw forbidden();
      }
      return baseUri;
    } catch (IllegalArgumentException exception) {
      throw forbidden();
    }
  }

  private int effectivePort(URI uri) {
    return uri.getPort() >= 0 ? uri.getPort() : uri.getScheme().equals("https") ? 443 : 80;
  }

  private BusinessException forbidden() {
    return new BusinessException(
        HttpStatus.BAD_REQUEST, "PLAYBACK_PROXY_TARGET_INVALID", "媒体代理路径无效");
  }

  private BusinessException unavailable() {
    return new BusinessException(
        HttpStatus.SERVICE_UNAVAILABLE, "EMBY_STREAM_UNAVAILABLE", "媒体流暂时不可用");
  }

  /** 持有上游 InputStream 的流式响应。 */
  public record ProxyResponse(
      int statusCode, HttpHeaders headers, InputStream body, URI upstreamUri)
      implements AutoCloseable {
    @Override
    public void close() throws IOException {
      body.close();
    }
  }
}
