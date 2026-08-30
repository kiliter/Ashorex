package com.shangan.identity.infrastructure;

import com.shangan.identity.domain.User;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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

  private final Clock clock;
  private final JwtEncoder jwtEncoder;
  private final SecureRandom secureRandom = new SecureRandom();

  public JwtService(Clock clock, @Value("${app.security.jwt-secret}") String jwtSecret) {
    if (jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalStateException("JWT_SECRET 至少需要 32 字节");
    }
    this.clock = clock;
    SecretKey key = new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    this.jwtEncoder = NimbusJwtEncoder.withSecretKey(key).build();
  }

  /** 签发包含最小身份声明的 15 分钟 Access Token。 */
  public String createAccessToken(User user) {
    Instant issuedAt = clock.instant();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer("shangan-server")
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plus(ACCESS_TOKEN_LIFETIME))
            .subject(user.id())
            .claim("username", user.username())
            .claim("role", user.role())
            .claim("timezone", user.timezone())
            .build();
    return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
  }

  /** 生成 256 bit 随机 Refresh Token 明文，只在创建时返回一次。 */
  public String createRefreshToken() {
    byte[] bytes = new byte[32];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
}
