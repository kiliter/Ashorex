package com.shangan.identity.infrastructure;

import com.shangan.identity.domain.User;
import com.shangan.identity.domain.UserRole;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/** 负责签发短期 Access Token，并生成、哈希不可预测的 Refresh Token。 */
@Component
public class JwtService {

  public static final Duration ACCESS_TOKEN_LIFETIME = Duration.ofMinutes(15);
  public static final Duration REFRESH_TOKEN_LIFETIME = Duration.ofDays(30);

  private final JwtEncoder jwtEncoder;
  private final SecureRandom secureRandom = new SecureRandom();

  public JwtService(@Value("${app.security.jwt-secret}") String jwtSecret) {
    if (jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalStateException("JWT_SECRET 至少需要 32 字节");
    }
    SecretKey key = new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    this.jwtEncoder = NimbusJwtEncoder.withSecretKey(key).build();
  }

  /** 签发包含最小身份声明的 15 分钟 Access Token；角色以数组声明承载。 */
  public AccessToken issueAccessToken(User user, Instant issuedAt) {
    Instant expiresAt = issuedAt.plus(ACCESS_TOKEN_LIFETIME);
    List<String> roles = user.roles().stream().map(UserRole::name).sorted().toList();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer("shangan-server")
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .subject(user.id())
            .claim("username", user.username())
            .claim("roles", roles)
            .claim("timezone", user.timezone())
            .build();
    String value = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    return new AccessToken(value, expiresAt);
  }

  /** 生成 256 bit 随机 Refresh Token 明文，只在创建时返回一次。 */
  public RefreshToken issueRefreshToken(Instant issuedAt) {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    return new RefreshToken(value, hashRefreshToken(value), issuedAt.plus(REFRESH_TOKEN_LIFETIME));
  }

  /** 使用 SHA-256 计算数据库中保存的 Refresh Token 摘要。 */
  public String hashRefreshToken(String refreshToken) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(refreshToken.getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("当前运行时不支持 SHA-256", exception);
    }
  }

  /** 已签发的 Access Token 及其过期时间。 */
  public record AccessToken(String value, Instant expiresAt) {}

  /** 已签发的 Refresh Token；明文只返回一次，数据库只保存哈希。 */
  public record RefreshToken(String value, String hash, Instant expiresAt) {}
}
