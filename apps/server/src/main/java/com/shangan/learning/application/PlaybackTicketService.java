package com.shangan.learning.application;

import com.shangan.common.api.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** 签发和验证两小时有效的 HMAC 播放票据，载荷不包含 Emby 凭据。 */
@Service
public class PlaybackTicketService {
  private static final Duration LIFETIME = Duration.ofHours(2);
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private final Clock clock;
  private final ObjectMapper json;
  private final byte[] secret;

  public PlaybackTicketService(
      Clock clock,
      ObjectMapper json,
      @Value("${PLAYBACK_TICKET_SECRET:${app.security.jwt-secret:}}") String secret) {
    if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
      throw new IllegalStateException("PLAYBACK_TICKET_SECRET 至少需要 32 字节");
    }
    this.clock = clock;
    this.json = json;
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
  }

  public String issue(String userId, String mediaItemId, String sessionId, String deviceId) {
    try {
      Claims claims =
          new Claims(
              userId,
              mediaItemId,
              sessionId,
              deviceId,
              clock.instant().plus(LIFETIME).toEpochMilli(),
              UUID.randomUUID().toString());
      String payload = ENCODER.encodeToString(json.writeValueAsBytes(claims));
      return payload + "." + ENCODER.encodeToString(sign(payload));
    } catch (Exception exception) {
      throw new IllegalStateException("无法签发播放票据");
    }
  }

  /** 验证签名、有效期和可选的当前用户绑定。 */
  public Claims verify(String ticket, String expectedUserId) {
    try {
      String[] parts = ticket.split("\\.", -1);
      if (parts.length != 2 || !MessageDigest.isEqual(sign(parts[0]), DECODER.decode(parts[1]))) {
        throw invalid();
      }
      Claims claims = json.readValue(DECODER.decode(parts[0]), Claims.class);
      if (claims.expiresAtEpochMs() <= clock.instant().toEpochMilli()) throw invalid();
      if (expectedUserId != null && !expectedUserId.equals(claims.userId())) {
        throw new BusinessException(
            HttpStatus.FORBIDDEN, "PLAYBACK_TICKET_FORBIDDEN", "播放票据不属于当前用户");
      }
      return claims;
    } catch (BusinessException exception) {
      throw exception;
    } catch (Exception exception) {
      throw invalid();
    }
  }

  private byte[] sign(String payload) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret, "HmacSHA256"));
    return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
  }

  private BusinessException invalid() {
    return new BusinessException(HttpStatus.UNAUTHORIZED, "PLAYBACK_TICKET_INVALID", "播放票据无效或已过期");
  }

  public record Claims(
      String userId,
      String mediaItemId,
      String sessionId,
      String deviceId,
      long expiresAtEpochMs,
      String nonce) {}
}
