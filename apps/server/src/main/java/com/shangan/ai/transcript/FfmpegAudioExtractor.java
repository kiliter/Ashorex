package com.shangan.ai.transcript;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 以受限外部进程抽取 16kHz 单声道 WAV，并按固定时长切片。 */
@Component
public class FfmpegAudioExtractor {
  private final String ffmpegPath;
  private final String ffprobePath;
  private final Duration timeout;
  private final int stderrLimitBytes;
  private final Path temporaryRoot;
  private final int chunkSeconds;

  public FfmpegAudioExtractor(
      @Value("${app.ai.transcription.ffmpeg-path:ffmpeg}") String ffmpegPath,
      @Value("${app.ai.transcription.ffprobe-path:ffprobe}") String ffprobePath,
      @Value("${app.ai.transcription.process-timeout:PT30M}") Duration timeout,
      @Value("${app.ai.transcription.stderr-limit-bytes:65536}") int stderrLimitBytes,
      @Value("${app.ai.transcription.temporary-root:${java.io.tmpdir}}") Path temporaryRoot,
      @Value("${app.ai.transcription.chunk-seconds:600}") int chunkSeconds) {
    this.ffmpegPath = ffmpegPath;
    this.ffprobePath = ffprobePath;
    this.timeout = timeout;
    this.stderrLimitBytes = Math.max(1024, stderrLimitBytes);
    this.temporaryRoot = temporaryRoot;
    this.chunkSeconds = Math.max(1, chunkSeconds);
  }

  /** 返回的结果必须关闭；关闭会递归删除本次任务的全部临时音频。调用失败时本方法自行清理。 */
  public ExtractedAudio extract(URI input, Map<String, String> headers) {
    validateInput(input);
    Path workingDirectory = null;
    try {
      Files.createDirectories(temporaryRoot);
      workingDirectory = Files.createTempDirectory(temporaryRoot, "shangan-transcription-");
      Path outputPattern = workingDirectory.resolve("chunk-%03d.wav");
      List<String> command = new ArrayList<>();
      command.addAll(List.of(ffmpegPath, "-hide_banner", "-nostdin", "-loglevel", "error"));
      if (!headers.isEmpty()) {
        command.add("-headers");
        command.add(toHeaderBlock(headers));
      }
      command.addAll(
          List.of(
              "-i",
              inputArgument(input),
              "-map",
              "0:a:0",
              "-vn",
              "-ac",
              "1",
              "-ar",
              "16000",
              "-c:a",
              "pcm_s16le",
              "-f",
              "segment",
              "-segment_time",
              Integer.toString(chunkSeconds),
              "-reset_timestamps",
              "1",
              "-y",
              outputPattern.toString()));
      runFfmpeg(command);

      List<Path> paths;
      try (var files = Files.list(workingDirectory)) {
        paths =
            files
                .filter(path -> path.getFileName().toString().matches("chunk-\\d{3}\\.wav"))
                .sorted()
                .toList();
      }
      if (paths.isEmpty()) throw new AudioExtractionException("FFmpeg 未生成音频切片");
      List<AudioChunk> chunks = new ArrayList<>();
      for (int index = 0; index < paths.size(); index++) {
        long durationMs = probeDurationMs(paths.get(index));
        long startMs = (long) index * chunkSeconds * 1000;
        chunks.add(new AudioChunk(index, startMs, startMs + durationMs, paths.get(index)));
      }
      return new ExtractedAudio(workingDirectory, List.copyOf(chunks));
    } catch (AudioExtractionException exception) {
      deleteRecursively(workingDirectory);
      throw exception;
    } catch (Exception exception) {
      deleteRecursively(workingDirectory);
      throw new AudioExtractionException("FFmpeg 音频抽取失败", exception);
    }
  }

  private void runFfmpeg(List<String> command) throws IOException, InterruptedException {
    Process process =
        new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    BoundedReader stderr = new BoundedReader(process.getErrorStream(), stderrLimitBytes);
    Thread reader = Thread.ofVirtual().start(stderr);
    boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    if (!completed) {
      process.destroyForcibly();
      process.waitFor();
      reader.join();
      throw new AudioExtractionException("FFmpeg 处理超时");
    }
    reader.join();
    if (process.exitValue() != 0) {
      // 诊断内容有长度上限，异常消息不拼接命令或请求头，避免泄露媒体凭据。
      throw new AudioExtractionException(
          "FFmpeg 处理失败（退出码 " + process.exitValue() + "）", stderr.content());
    }
  }

  private long probeDurationMs(Path audio) throws IOException, InterruptedException {
    Process process =
        new ProcessBuilder(
                ffprobePath,
                "-v",
                "error",
                "-show_entries",
                "format=duration",
                "-of",
                "default=noprint_wrappers=1:nokey=1",
                audio.toString())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    byte[] output;
    try (InputStream stream = process.getInputStream()) {
      output = stream.readNBytes(1024);
    }
    if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
      process.destroyForcibly();
      throw new AudioExtractionException("FFprobe 处理超时");
    }
    if (process.exitValue() != 0) throw new AudioExtractionException("FFprobe 读取音频失败");
    try {
      return Math.max(
          1,
          Math.round(Double.parseDouble(new String(output, StandardCharsets.UTF_8).trim()) * 1000));
    } catch (NumberFormatException exception) {
      throw new AudioExtractionException("FFprobe 返回时长格式无效", exception);
    }
  }

  private String toHeaderBlock(Map<String, String> headers) {
    StringBuilder block = new StringBuilder();
    headers.forEach(
        (name, value) -> {
          if (!name.matches("[A-Za-z0-9-]+") || value.contains("\r") || value.contains("\n")) {
            throw new AudioExtractionException("媒体请求头格式无效");
          }
          block.append(name).append(": ").append(value).append("\r\n");
        });
    return block.toString();
  }

  private void validateInput(URI input) {
    if (input == null || input.getScheme() == null) {
      throw new AudioExtractionException("媒体输入地址无效");
    }
    if (!List.of("file", "http", "https").contains(input.getScheme().toLowerCase())) {
      throw new AudioExtractionException("媒体输入协议不受支持");
    }
  }

  private String inputArgument(URI input) {
    return "file".equalsIgnoreCase(input.getScheme())
        ? Path.of(input).toString()
        : input.toString();
  }

  private static void deleteRecursively(Path directory) {
    if (directory == null || !Files.exists(directory)) return;
    try (var paths = Files.walk(directory)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ignored) {
                  // 最佳努力清理；业务错误仍保留原始原因。
                }
              });
    } catch (IOException ignored) {
      // 临时目录清理失败由运维目录清理策略兜底，不覆盖业务异常。
    }
  }

  public record AudioChunk(int index, long startMs, long endMs, Path path) {}

  public record ExtractedAudio(Path workingDirectory, List<AudioChunk> chunks)
      implements AutoCloseable {
    @Override
    public void close() {
      deleteRecursively(workingDirectory);
    }
  }

  public static class AudioExtractionException extends RuntimeException {
    private final String diagnostic;

    AudioExtractionException(String message) {
      this(message, "");
    }

    AudioExtractionException(String message, String diagnostic) {
      super(message);
      this.diagnostic = diagnostic;
    }

    AudioExtractionException(String message, Throwable cause) {
      super(message, cause);
      this.diagnostic = "";
    }

    /** 仅供受控诊断使用，内容已截断；不得直接返回给 API 或保存完整密钥。 */
    String diagnostic() {
      return diagnostic;
    }
  }

  private static final class BoundedReader implements Runnable {
    private final InputStream input;
    private final int limit;
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

    private BoundedReader(InputStream input, int limit) {
      this.input = input;
      this.limit = limit;
    }

    @Override
    public void run() {
      byte[] buffer = new byte[4096];
      try (input) {
        int read;
        while ((read = input.read(buffer)) >= 0) {
          int remaining = limit - captured.size();
          if (remaining > 0) captured.write(buffer, 0, Math.min(read, remaining));
        }
      } catch (IOException ignored) {
        // 子进程结束时流可能关闭；主线程按退出码判断处理结果。
      }
    }

    String content() {
      return captured.toString(StandardCharsets.UTF_8);
    }
  }
}
