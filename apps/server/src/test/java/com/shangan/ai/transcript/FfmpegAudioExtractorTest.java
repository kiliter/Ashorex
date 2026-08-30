package com.shangan.ai.transcript;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 使用真实 FFmpeg 验证 16kHz 单声道、确定性切片和临时目录清理。 */
class FfmpegAudioExtractorTest {
  private static final String FFMPEG = executable("ffmpeg");
  private static final String FFPROBE = executable("ffprobe");

  @TempDir Path temporaryDirectory;
  Path fixture;

  @BeforeEach
  void createAudioFixture() throws Exception {
    fixture = temporaryDirectory.resolve("fixture.wav");
    Process process =
        new ProcessBuilder(
                FFMPEG,
                "-hide_banner",
                "-loglevel",
                "error",
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=1000:duration=5",
                "-y",
                fixture.toString())
            .start();
    assertThat(process.waitFor()).isZero();
  }

  @Test
  void extractsMono16KhzAudioIntoDeterministicChunksAndDeletesTemporaryFiles() throws Exception {
    var extractor = extractor(FFMPEG, Duration.ofSeconds(20));
    Path workingDirectory;

    try (var extracted = extractor.extract(fixture.toUri(), Map.of())) {
      workingDirectory = extracted.workingDirectory();
      assertThat(extracted.chunks()).hasSize(3);
      assertThat(extracted.chunks())
          .extracting(chunk -> chunk.path().getFileName().toString())
          .containsExactly("chunk-000.wav", "chunk-001.wav", "chunk-002.wav");

      for (var chunk : extracted.chunks()) {
        String properties =
            run(
                FFPROBE,
                "-v",
                "error",
                "-select_streams",
                "a:0",
                "-show_entries",
                "stream=sample_rate,channels",
                "-of",
                "default=noprint_wrappers=1",
                chunk.path().toString());
        assertThat(properties).contains("sample_rate=16000", "channels=1");
      }
      assertThat(workingDirectory).exists();
    }

    assertThat(workingDirectory).doesNotExist();
  }

  @Test
  void rejectsNonZeroExitAndCleansFailedWorkingDirectory() throws Exception {
    long directoriesBefore = directoryCount();
    var extractor = extractor("/usr/bin/false", Duration.ofSeconds(2));

    assertThatThrownBy(() -> extractor.extract(fixture.toUri(), Map.of()))
        .isInstanceOf(FfmpegAudioExtractor.AudioExtractionException.class)
        .hasMessageContaining("FFmpeg");

    assertThat(directoryCount()).isEqualTo(directoriesBefore);
  }

  private FfmpegAudioExtractor extractor(String ffmpeg, Duration timeout) {
    return new FfmpegAudioExtractor(ffmpeg, FFPROBE, timeout, 16_384, temporaryDirectory, 2);
  }

  private long directoryCount() throws Exception {
    try (var entries = Files.list(temporaryDirectory)) {
      return entries.filter(Files::isDirectory).count();
    }
  }

  private String run(String... command) throws Exception {
    Process process = new ProcessBuilder(command).start();
    String output = new String(process.getInputStream().readAllBytes());
    assertThat(process.waitFor()).isZero();
    return output;
  }

  /** 根验证使用精简 PATH，因此测试显式解析常见安装位置。 */
  private static String executable(String name) {
    for (String directory : List.of("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")) {
      Path candidate = Path.of(directory, name);
      if (Files.isExecutable(candidate)) return candidate.toString();
    }
    return name;
  }
}
