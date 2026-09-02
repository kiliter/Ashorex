package com.shangan.media.emby;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;

/** 从 Emby 媒体路径生成不可逆稳定指纹；原始路径只在本方法调用期间存在。 */
public final class EmbySourceFingerprint {

  private static final String VERSION_PREFIX = "path-sha256-v1:";

  private EmbySourceFingerprint() {}

  /** 规范化目录分隔符后计算 SHA-256；空路径不参与自动来源匹配。 */
  public static String fromPath(String path) {
    if (path == null || path.isBlank()) {
      return null;
    }
    String normalized =
        Normalizer.normalize(path.trim().replace('\\', '/'), Normalizer.Form.NFC)
            .replaceAll("/{2,}", "/");
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
      return VERSION_PREFIX + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      // Java 21 必须提供 SHA-256；转换为不可恢复错误，避免退化为不安全明文身份。
      throw new IllegalStateException("当前 Java 运行时不支持 SHA-256", exception);
    }
  }
}
