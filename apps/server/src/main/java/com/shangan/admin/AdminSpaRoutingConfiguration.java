package com.shangan.admin;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 管理后台 SPA 的路由转发。
 *
 * <p>Vue Router 使用 history 模式，直接打开或刷新 `/admin/users` 时浏览器会真的请求该路径。 静态资源处理器只认识 `/admin/index.html` 与
 * `/admin/assets/**`，因此这里把每个客户端路由 显式转发到外壳页，由前端接管渲染。
 *
 * <p>采用「显式列出」而不是通配转发：`/admin/**` 通配会连 `/admin/api/**` 和缺失的静态资源 一起吃掉，导致 404 被掩盖成
 * 200，排查困难。新增前端路由时必须同步加到这里。
 */
@Configuration(proxyBeanMethods = false)
public class AdminSpaRoutingConfiguration implements WebMvcConfigurer {

  /** 与 apps/admin-web/src/router/index.ts 中的路由表保持一致。 */
  private static final String[] CLIENT_ROUTES = {
    "/admin",
    "/admin/",
    "/admin/login",
    "/admin/presence",
    "/admin/nags",
    "/admin/nag-policy",
    "/admin/courses",
    "/admin/emby-sync",
    "/admin/users",
    "/admin/supervisions",
    "/admin/archive",
    "/admin/settings",
  };

  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    for (String route : CLIENT_ROUTES) {
      registry.addViewController(route).setViewName("forward:/admin/index.html");
    }
  }
}
