package com.shangan.catalog.api;

import com.shangan.catalog.application.CatalogQueryService;
import com.shangan.catalog.domain.LearningResource;
import com.shangan.common.auth.CurrentUser;
import com.shangan.media.emby.EmbyStreamProxy;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 播放代理。
 *
 * <p>V2 取消播放票据 HMAC，改为普通 Bearer 鉴权 + 归属与可用性校验。 Emby API Key 不出服务端，目标主机由配置固定， 流式转发不整体缓冲（见 Spec
 * 10.7）。
 */
@RestController
@RequestMapping("/api/v1/playback")
public class PlaybackController {

  private final CatalogQueryService catalog;
  private final EmbyStreamProxy proxy;

  public PlaybackController(CatalogQueryService catalog, EmbyStreamProxy proxy) {
    this.catalog = catalog;
    this.proxy = proxy;
  }

  @GetMapping("/{resourceId}/stream")
  ResponseEntity<StreamingResponseBody> stream(
      CurrentUser currentUser,
      @PathVariable String resourceId,
      @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
      @RequestHeader(value = "If-Range", required = false) String ifRange) {
    LearningResource resource = catalog.requireVisibleResource(resourceId);
    // Item ID 只标识媒体项，不能冒充 MediaSourceId；由 Emby 为该项选择默认媒体源。
    String pathAndQuery = "/Videos/" + resource.externalRef() + "/stream?static=true";
    EmbyStreamProxy.ProxyResponse upstream = proxy.open(pathAndQuery, range, ifRange);
    StreamingResponseBody body =
        outputStream -> {
          try (InputStream source = upstream.body()) {
            transfer(source, outputStream);
          }
        };
    return ResponseEntity.status(HttpStatus.valueOf(upstream.statusCode()))
        .headers(upstream.headers())
        .body(body);
  }

  /** 固定 64KB 缓冲逐段转发，避免把整段视频读入内存。 */
  private void transfer(InputStream source, OutputStream target) throws IOException {
    byte[] buffer = new byte[64 * 1024];
    int read;
    while ((read = source.read(buffer)) != -1) {
      target.write(buffer, 0, read);
      target.flush();
    }
  }
}
