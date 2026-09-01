package com.shangan.common.config;

import com.shangan.common.api.RequestLoggingInterceptor;
import com.shangan.common.auth.CurrentUserArgumentResolver;
import com.shangan.identity.application.AuthService;
import com.shangan.identity.domain.User;
import com.shangan.identity.infrastructure.UserRepository;
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
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 分离管理后台 Session 安全与 App Bearer Token 安全。 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  /** 管理后台使用 Session、表单登录和 CSRF，仅允许 ADMIN。 */
  @Bean
  @Order(1)
  SecurityFilterChain adminSecurity(HttpSecurity http) throws Exception {
    http.securityMatcher("/admin/**")
        .authorizeHttpRequests(
            requests ->
                requests.requestMatchers("/admin/login").permitAll().anyRequest().hasRole("ADMIN"))
        .formLogin(
            form ->
                form.loginPage("/admin/login")
                    .loginProcessingUrl("/admin/login")
                    .defaultSuccessUrl("/admin/health", true)
                    .permitAll())
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/admin/login"))
                    // 管理台会话失效或角色不足时统一返回登录页，不暴露 Whitelabel 403。
                    .accessDeniedHandler(
                        (request, response, exception) ->
                            response.sendRedirect(
                                request.getContextPath() + "/admin/login?denied=true")))
        .logout(logout -> logout.logoutUrl("/admin/logout"));
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
                    .requestMatchers(HttpMethod.GET, "/api/v1/playback/**")
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
  JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(
        jwt -> List.of(new SimpleGrantedAuthority("ROLE_" + jwt.getClaimAsString("role"))));
    return converter;
  }

  /** 管理后台的用户名密码认证同样读取本地用户表。 */
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
      UserDetails details =
          org.springframework.security.core.userdetails.User.withUsername(user.username())
              .password(user.passwordHash())
              .roles(user.role())
              .disabled(!user.enabled())
              .build();
      return details;
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
