package com.shangan.catalog.api;

import com.shangan.catalog.application.CatalogQueryService;
import com.shangan.catalog.domain.LearningResource;
import com.shangan.common.auth.CurrentUser;
import com.shangan.media.emby.EmbyStreamProxy;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 播放代理。
 *
 * <p>V2 取消播放票据 HMAC，改为普通 Bearer 鉴权 + 归属与可用性校验。 Emby API Key 不出服务端，目标主机由配置固定， 流式转发不整体缓冲（见 Spec
 * 10.7）。
 */
@RestController
@RequestMapping("/api/v1/playback")
public class PlaybackController {

  private static final Logger log = LoggerFactory.getLogger(PlaybackController.class);

  private final CatalogQueryService catalog;
  private final EmbyStreamProxy proxy;

  public PlaybackController(CatalogQueryService catalog, EmbyStreamProxy proxy) {
    this.catalog = catalog;
    this.proxy = proxy;
  }

  @GetMapping("/{resourceId}/stream")
  // 在请求虚拟线程同步传输，不进入 Servlet 异步生命周期的默认 30 秒总超时。
  // 不关闭客户端响应流；无论正常完成、上游失败或客户端断开，都关闭上游连接。
  void stream(
      CurrentUser currentUser,
      @PathVariable String resourceId,
      @RequestHeader(value = HttpHeaders.RANGE, required = false) String range,
      @RequestHeader(value = "If-Range", required = false) String ifRange,
      HttpServletResponse response)
      throws IOException {
    LearningResource resource = catalog.requireVisibleResource(resourceId);
    // Item ID 只标识媒体项，不能冒充 MediaSourceId；由 Emby 为该项选择默认媒体源。
    String pathAndQuery = "/Videos/" + resource.externalRef() + "/stream?static=true";
    long started = System.nanoTime();
    try (EmbyStreamProxy.ProxyResponse upstream = proxy.open(pathAndQuery, range, ifRange)) {
      response.setStatus(upstream.statusCode());
      upstream
          .headers()
          .forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
      transfer(
          upstream.body(),
          response.getOutputStream(),
          upstream.headers().getContentLength(),
          started);
    }
  }

  /** 固定 64KB 缓冲转发；只记录计数与异常类型，不记录地址、请求头或异常原文。 */
  private void transfer(InputStream source, OutputStream target, long expectedBytes, long started)
      throws IOException {
    byte[] buffer = new byte[64 * 1024];
    long readBytes = 0;
    long writtenBytes = 0;
    String phase = "READ_UPSTREAM";
    String outcome = "FAILED";
    try {
      while (true) {
        phase = "READ_UPSTREAM";
        int read = source.read(buffer);
        if (read == -1) break;
        readBytes += read;
        phase = "WRITE_CLIENT";
        target.write(buffer, 0, read);
        writtenBytes += read;
        target.flush();
      }
      if (expectedBytes >= 0 && readBytes != expectedBytes) {
        outcome = "TRUNCATED";
        throw new IOException("媒体响应正文长度不匹配");
      }
      outcome = "COMPLETE";
    } catch (IOException failure) {
      log.warn("视频流中断 phase={} errorType={}", phase, failure.getClass().getSimpleName());
      throw failure;
    } finally {
      log.info(
          "视频流传输结束 outcome={} phase={} expectedBytes={} readBytes={} writtenBytes={} durationMs={}",
          outcome,
          phase,
          expectedBytes,
          readBytes,
          writtenBytes,
          (System.nanoTime() - started) / 1_000_000);
    }
  }
}
