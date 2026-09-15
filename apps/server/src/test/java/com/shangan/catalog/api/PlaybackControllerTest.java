package com.shangan.catalog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.shangan.catalog.application.CatalogQueryService;
import com.shangan.catalog.domain.CatalogStatus;
import com.shangan.catalog.domain.LearningResource;
import com.shangan.catalog.domain.ResourceType;
import com.shangan.common.auth.CurrentUser;
import com.shangan.media.emby.EmbyStreamProxy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** 不启动数据库，验证真实 MVC 响应不进入异步超时、Range 透传与上游关闭。 */
class PlaybackControllerTest {
  private final CatalogQueryService catalog = mock(CatalogQueryService.class);
  private final EmbyStreamProxy proxy = mock(EmbyStreamProxy.class);

  @Test
  void streamsWithoutAsyncTimeoutAndPreservesRange() throws Exception {
    var source = spy(new ByteArrayInputStream(new byte[] {1, 2, 3}));
    var response = perform(source, 3);
    assertThat(response.getRequest().isAsyncStarted()).isFalse();
    assertThat(response.getResponse().getStatus()).isEqualTo(206);
    assertThat(response.getResponse().getHeader("Content-Range")).isEqualTo("bytes 0-2/3");
    assertThat(response.getResponse().getContentAsByteArray()).containsExactly(1, 2, 3);
    verify(proxy).open("/Videos/item-123/stream?static=true", "bytes=0-2", "etag");
    verify(source).close();
  }

  @Test
  void closesUpstreamWhenReadFails() throws Exception {
    var source = mock(InputStream.class);
    when(source.read(any(byte[].class))).thenThrow(new IOException("test read failure"));
    assertThatThrownBy(() -> perform(source, 3)).isInstanceOf(IOException.class);
    verify(source).close();
  }

  @Test
  void rejectsTruncatedBodyAndClosesUpstream() throws Exception {
    var source = spy(new ByteArrayInputStream(new byte[] {1}));
    assertThatThrownBy(() -> perform(source, 3)).isInstanceOf(IOException.class);
    verify(source).close();
  }

  @Test
  void closesUpstreamWhenClientDisconnects() throws Exception {
    var source = spy(new ByteArrayInputStream(new byte[] {1, 2, 3}));
    // 先配置协议边界，再单独注入写入失败的客户端输出流。
    perform(new ByteArrayInputStream(new byte[] {1, 2, 3}), 3);
    when(proxy.open(anyString(), any(), any()))
        .thenReturn(
            new EmbyStreamProxy.ProxyResponse(
                206, new HttpHeaders(), source, URI.create("https://example.invalid/video")));
    var response = mock(jakarta.servlet.http.HttpServletResponse.class);
    var output = mock(jakarta.servlet.ServletOutputStream.class);
    when(response.getOutputStream()).thenReturn(output);
    doThrow(new IOException("test client closed"))
        .when(output)
        .write(any(byte[].class), anyInt(), anyInt());
    assertThatThrownBy(
            () ->
                new PlaybackController(catalog, proxy)
                    .stream(
                        new CurrentUser("user", "学习者", "LEARNER", "Asia/Shanghai"),
                        "resource",
                        null,
                        null,
                        response))
        .isInstanceOf(IOException.class);
    verify(source).close();
  }

  /** 测试替换媒体协议边界和认证解析，不替换被测 Controller。 */
  private org.springframework.test.web.servlet.MvcResult perform(InputStream source, long length)
      throws Exception {
    when(catalog.requireVisibleResource("resource"))
        .thenReturn(
            new LearningResource(
                "resource",
                "course",
                ResourceType.VIDEO,
                "课程",
                1,
                1000L,
                null,
                "item-123",
                null,
                true,
                CatalogStatus.ACTIVE,
                null));
    var headers = new HttpHeaders();
    headers.setContentType(org.springframework.http.MediaType.valueOf("video/mp4"));
    headers.setContentLength(length);
    headers.set("Content-Range", "bytes 0-2/3");
    when(proxy.open(anyString(), any(), any()))
        .thenReturn(
            new EmbyStreamProxy.ProxyResponse(
                206, headers, source, URI.create("https://example.invalid/video")));
    var mvc =
        MockMvcBuilders.standaloneSetup(new PlaybackController(catalog, proxy))
            .setCustomArgumentResolvers(
                new HandlerMethodArgumentResolver() {
                  @Override
                  public boolean supportsParameter(MethodParameter parameter) {
                    return parameter.getParameterType() == CurrentUser.class;
                  }

                  @Override
                  public Object resolveArgument(
                      MethodParameter parameter,
                      ModelAndViewContainer container,
                      NativeWebRequest request,
                      WebDataBinderFactory binder) {
                    return new CurrentUser("user", "学习者", "LEARNER", "Asia/Shanghai");
                  }
                })
            .build();
    return mvc.perform(
            get("/api/v1/playback/resource/stream")
                .header("Range", "bytes=0-2")
                .header("If-Range", "etag"))
        .andReturn();
  }
}
