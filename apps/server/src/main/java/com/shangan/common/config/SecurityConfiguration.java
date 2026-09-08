package com.shangan.common.config;

import com.shangan.common.api.RequestLoggingInterceptor;
import com.shangan.common.auth.CurrentUserArgumentResolver;
import com.shangan.identity.application.AuthService;
import com.shangan.identity.domain.User;
import com.shangan.identity.domain.UserRole;
import com.shangan.identity.infrastructure.UserRepository;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 分离管理后台 Session 安全与 App Bearer Token 安全。 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  /**
   * 管理后台使用 Session + CSRF，仅允许 ADMIN。
   *
   * <p>前端是 Vue SPA（ADR-0033）：
   *
   * <ul>
   *   <li>静态资源 `/admin`、`/admin/index.html`、`/admin/assets/**` 匿名可读， 真正的数据都在 `/admin/api/**`
   *       后面，未登录只会拿到一个空壳；
   *   <li>CSRF 令牌通过非 HttpOnly 的 `XSRF-TOKEN` Cookie 下发，SPA 回填到 `X-XSRF-TOKEN`；
   *   <li>未认证访问 `/admin/api/**` 返回 401 JSON，而不是 302 跳登录页， 否则 fetch 会拿到登录页 HTML 而无法判断状态。
   * </ul>
   */
  @Bean
  @Order(1)
  SecurityFilterChain adminSecurity(HttpSecurity http, UserRepository users) throws Exception {
    CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
    csrfRepository.setCookiePath("/");

    http.securityMatcher("/admin/**")
        .csrf(
            csrf ->
                csrf.csrfTokenRepository(csrfRepository)
                    // 默认的 XorCsrfTokenRequestAttributeHandler 会对令牌做 BREACH 编码，
                    // 导致 Cookie 里的值无法直接回填到请求头。SPA 场景改用原始值处理器。
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
        .authorizeHttpRequests(
            requests ->
                requests
                    // 登录与会话探测必须匿名可访问，否则前端拿不到 CSRF 令牌。
                    .requestMatchers("/admin/api/session", "/admin/api/session/login")
                    .permitAll()
                    // 业务数据全部要求 ADMIN。
                    .requestMatchers("/admin/api/**")
                    .access(
                        (authentication, context) ->
                            new org.springframework.security.authorization.AuthorizationDecision(
                                activeAdministrator(authentication.get(), users)))
                    // 其余是 SPA 外壳与静态资源：本身不含业务数据，匿名可读，
                    // 未登录时前端路由守卫会把用户导向登录视图。
                    .anyRequest()
                    .permitAll())
        .formLogin(
            form ->
                form.loginProcessingUrl("/admin/api/session/login")
                    .successHandler(
                        (request, response, authentication) ->
                            response.setStatus(HttpServletResponse.SC_NO_CONTENT))
                    .failureHandler(AdminSecurityHandlers.loginFailureHandler())
                    .permitAll())
        .exceptionHandling(
            exceptions ->
                exceptions
                    // 后台只有 API 会触发未认证/无权限，统一返回 Problem Details，
                    // 让前端能区分「会话过期」与「网络错误」。
                    .authenticationEntryPoint(AdminSecurityHandlers.authenticationEntryPoint())
                    .accessDeniedHandler(AdminSecurityHandlers.accessDeniedHandler()))
        .logout(
            logout ->
                logout
                    .logoutUrl("/admin/api/session/logout")
                    // 登出会清空 XSRF-TOKEN Cookie。这里不尝试补发新令牌：
                    // LogoutSuccessHandler 跑在 CsrfFilter 之后，此时写 Cookie 的时机已过，
                    // 实测无效。新令牌由前端在下次需要时通过 GET /admin/api/session 主动获取。
                    .logoutSuccessHandler(
                        (request, response, authentication) ->
                            response.setStatus(HttpServletResponse.SC_NO_CONTENT)));
    return http.build();
  }

  /** App API 使用无状态 Bearer Token，认证入口不要求 CSRF。 */
  @Bean
  @Order(2)
  SecurityFilterChain apiSecurity(
      HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(
            sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers("/api/v1/auth/**", "/actuator/health")
                    .permitAll()
                    .requestMatchers(HttpMethod.GET, "/admin/login")
                    .permitAll()
                    .requestMatchers("/api/v1/**")
                    .authenticated()
                    .anyRequest()
                    .permitAll())
        .oauth2ResourceServer(
            resourceServer ->
                resourceServer.jwt(
                    jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
    return http.build();
  }

  @Bean
  JwtDecoder jwtDecoder(@Value("${app.security.jwt-secret}") String jwtSecret) {
    if (jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalStateException("JWT_SECRET 至少需要 32 字节");
    }
    SecretKey key = new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    return NimbusJwtDecoder.withSecretKey(key).build();
  }

  @Bean
  JwtAuthenticationConverter jwtAuthenticationConverter(UserRepository users) {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(
        jwt -> {
          // 归档与角色撤销即时生效，不能等待既有 Access Token 过期。
          User user =
              users
                  .findById(jwt.getSubject())
                  .filter(User::active)
                  .orElseThrow(
                      () ->
                          new org.springframework.security.oauth2.core
                              .OAuth2AuthenticationException("invalid_token"));
          return user.roles().stream()
              .map(
                  role ->
                      (org.springframework.security.core.GrantedAuthority)
                          new SimpleGrantedAuthority("ROLE_" + role.name()))
              .toList();
        });
    return converter;
  }

  /** 已建立的后台 Session 也必须服从账号当前状态，防止归档后仍可写入。 */
  static boolean activeAdministrator(
      org.springframework.security.core.Authentication authentication, UserRepository users) {
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication
            instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)
      return false;
    return users
        .findByUsername(authentication.getName())
        .filter(User::active)
        .filter(user -> user.hasRole(UserRole.ADMIN))
        .isPresent();
  }

  /** 管理后台的用户名密码认证同样读取本地用户表，角色来自 user_roles。 */
  @Bean
  UserDetailsService userDetailsService(UserRepository users) {
    return username -> {
      User user =
          users
              .findByUsername(username)
              .orElseThrow(
                  () ->
                      new org.springframework.security.core.userdetails.UsernameNotFoundException(
                          "用户不存在"));
      String[] roles = user.roles().stream().map(Enum::name).toArray(String[]::new);
      return org.springframework.security.core.userdetails.User.withUsername(user.username())
          .password(user.passwordHash())
          .roles(roles.length == 0 ? new String[] {"LEARNER"} : roles)
          .disabled(!user.active())
          .build();
    };
  }

  @Bean
  WebMvcConfigurer currentUserWebMvcConfigurer(RequestLoggingInterceptor requestLogging) {
    CurrentUserArgumentResolver resolver = new CurrentUserArgumentResolver();
    return new WebMvcConfigurer() {
      @Override
      public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(resolver);
      }

      @Override
      public void addInterceptors(
          org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
        registry.addInterceptor(requestLogging);
      }
    };
  }

  /** 首次启动管理员由环境变量引导创建，不记录明文密码。 */
  @Bean
  ApplicationRunner bootstrapAdministrator(
      AuthService authService,
      @Value("${app.security.bootstrap-admin-username:}") String username,
      @Value("${app.security.bootstrap-admin-password:}") String password) {
    return arguments -> authService.bootstrapAdministrator(username, password);
  }
}
