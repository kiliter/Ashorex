package com.shangan.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 异步重新分派不能覆盖初始计时属性，不依赖等待或系统时钟精度。 */
class RequestLoggingInterceptorTest {
  @Test
  void preservesOriginalStartOnRedispatch() {
    var request = new MockHttpServletRequest();
    var response = new MockHttpServletResponse();
    var interceptor = new RequestLoggingInterceptor();
    String key = RequestLoggingInterceptor.class.getName() + ".startedAt";
    request.setAttribute(key, 123L);
    interceptor.preHandle(request, response, new Object());
    assertThat(request.getAttribute(key)).isEqualTo(123L);
  }
}
