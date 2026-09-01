package com.shangan.admin;

import com.shangan.ai.content.application.ContentLanguageModel;
import com.shangan.ai.content.infrastructure.OpenAiCompatibleAsrClient;
import com.shangan.common.api.BusinessException;
import com.shangan.common.integration.IntegrationSettingsProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 使用已保存的运行时配置执行管理员主动触发的 ASR 与 LLM 连通性测试。 */
@Service
public class IntegrationConnectionTestService {

  private final IntegrationSettingsProvider settings;
  private final OpenAiCompatibleAsrClient asr;
  private final ContentLanguageModel llm;
  private final Path tempDirectory;

  public IntegrationConnectionTestService(
      IntegrationSettingsProvider settings,
      OpenAiCompatibleAsrClient asr,
      ContentLanguageModel llm,
      @Value("${app.content.temp-directory:${java.io.tmpdir}}") String tempDirectory) {
    this.settings = settings;
    this.asr = asr;
    this.llm = llm;
    this.tempDirectory = Path.of(tempDirectory).toAbsolutePath().normalize();
  }

  /** 将内置“你好”MP3 解码到临时目录，调用完成后无论成功失败都删除。 */
  public String testAsr() {
    Path audio = null;
    try {
      Files.createDirectories(tempDirectory);
      audio = Files.createTempFile(tempDirectory, "shangan-asr-test-", ".mp3");
      ClassPathResource resource = new ClassPathResource("asr-test/nihao.mp3.b64");
      byte[] encoded;
      try (var input = resource.getInputStream()) {
        encoded = input.readAllBytes();
      }
      Files.write(audio, Base64.getMimeDecoder().decode(encoded));
      return safeResult(asr.transcribe(audio, settings.current().asr()));
    } catch (BusinessException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new BusinessException(
          HttpStatus.INTERNAL_SERVER_ERROR, "ASR_TEST_AUDIO_FAILED", "ASR 测试音频准备失败");
    } finally {
      deleteQuietly(audio);
    }
  }

  /** 发送不包含任何业务数据的最小提示词，仅验证 Chat Completions 是否可用。 */
  public String testLlm() {
    var result = llm.generate("你是服务连通性检查助手。", "请只回复：连接成功", settings.current().llm(), false);
    return safeResult(result.text());
  }

  private String safeResult(String value) {
    String compact = value == null ? "" : value.replaceAll("\\s+", " ").trim();
    return compact.length() <= 160 ? compact : compact.substring(0, 160) + "…";
  }

  private void deleteQuietly(Path audio) {
    if (audio == null) return;
    try {
      Files.deleteIfExists(audio);
    } catch (IOException ignored) {
      // 临时文件清理失败不覆盖真实的外部服务测试结果。
    }
  }
}
