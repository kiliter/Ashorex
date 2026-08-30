package com.shangan.learning.api;

import com.shangan.common.api.BusinessException;
import com.shangan.learning.application.PlaybackSessionService;
import com.shangan.media.emby.EmbyStreamProxy;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** 使用播放票据保护 Range 与 HLS 流式代理路由。 */
@RestController
@RequestMapping("/api/v1/playback/{ticket}")
public class PlaybackProxyController {
  private static final int MAX_MANIFEST_BYTES = 2_000_000;

  private final PlaybackSessionService playback;
  private final EmbyStreamProxy proxy;

  public PlaybackProxyController(PlaybackSessionService playback, EmbyStreamProxy proxy) {
    this.playback = playback;
    this.proxy = proxy;
  }

  @GetMapping("/stream")
  ResponseEntity<StreamingResponseBody> stream(
      @PathVariable String ticket, HttpServletRequest request) {
    var context = playback.verify(ticket);
    return streamResponse(
        context.upstreamPath(), request.getHeader("Range"), request.getHeader("If-Range"));
  }

  @GetMapping("/master.m3u8")
  ResponseEntity<byte[]> master(@PathVariable String ticket) {
    var context = playback.verify(ticket);
    if (!context.hls()) throw invalidPath();
    return manifestResponse(ticket, context.upstreamPath());
  }

  @GetMapping("/proxy/**")
  ResponseEntity<?> hlsChild(@PathVariable String ticket, HttpServletRequest request) {
    var context = playback.verify(ticket);
    String prefix = "/api/v1/playback/" + ticket + "/proxy";
    String requestUri = request.getRequestURI();
    if (!requestUri.startsWith(prefix)) throw invalidPath();
    String path = requestUri.substring(prefix.length());
    if (request.getQueryString() != null) path += "?" + request.getQueryString();
    if (!context.hls() || !context.allows(path)) throw invalidPath();
    if (path.toLowerCase().contains(".m3u8")) return manifestResponse(ticket, path);
    return streamResponse(path, request.getHeader("Range"), request.getHeader("If-Range"));
  }

  private ResponseEntity<byte[]> manifestResponse(String ticket, String path) {
    try (var upstream = proxy.open(path, null, null)) {
      byte[] input = upstream.body().readNBytes(MAX_MANIFEST_BYTES + 1);
      if (input.length > MAX_MANIFEST_BYTES) {
        throw new BusinessException(HttpStatus.BAD_GATEWAY, "HLS_MANIFEST_TOO_LARGE", "媒体清单过大");
      }
      String rewritten =
          proxy.rewriteManifest(
              ticket, new String(input, StandardCharsets.UTF_8), upstream.upstreamUri());
      HttpHeaders headers = new HttpHeaders();
      headers.set("Content-Type", "application/vnd.apple.mpegurl");
      return new ResponseEntity<>(
          rewritten.getBytes(StandardCharsets.UTF_8),
          headers,
          HttpStatusCode.valueOf(upstream.statusCode()));
    } catch (java.io.IOException exception) {
      throw new BusinessException(HttpStatus.BAD_GATEWAY, "HLS_MANIFEST_INVALID", "媒体清单不可用");
    }
  }

  private ResponseEntity<StreamingResponseBody> streamResponse(
      String path, String range, String ifRange) {
    var upstream = proxy.open(path, range, ifRange);
    StreamingResponseBody body =
        output -> {
          try (upstream) {
            upstream.body().transferTo(output);
          }
        };
    return new ResponseEntity<>(
        body, upstream.headers(), HttpStatusCode.valueOf(upstream.statusCode()));
  }

  private BusinessException invalidPath() {
    return new BusinessException(HttpStatus.BAD_REQUEST, "PLAYBACK_PROXY_PATH_INVALID", "播放代理路径无效");
  }
}
