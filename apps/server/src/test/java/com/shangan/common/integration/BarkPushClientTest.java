package com.shangan.common.integration;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 重定向和伪成功正文不能绕过可信源站或伪装投递成功。 */
class BarkPushClientTest {
  @Test
  void 禁止重定向到其他目的地且拒绝业务失败() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var redirected = new AtomicInteger();
    server.createContext(
        "/push",
        exchange -> {
          exchange.getResponseHeaders().add("Location", "/secret-target");
          exchange.sendResponseHeaders(307, -1);
          exchange.close();
        });
    server.createContext(
        "/secret-target",
        exchange -> {
          redirected.incrementAndGet();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    try {
      var client = new BarkPushClient();
      assertThat(
              client.send(
                  "http://127.0.0.1:" + server.getAddress().getPort(),
                  "test-only-key",
                  1,
                  "标题",
                  "正文"))
          .isFalse();
      assertThat(redirected.get()).isZero();
    } finally {
      server.stop(0);
    }
  }
}
