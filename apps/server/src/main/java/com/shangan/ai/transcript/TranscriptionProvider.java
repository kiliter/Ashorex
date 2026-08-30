package com.shangan.ai.transcript;

import java.nio.file.Path;
import java.util.List;

/** ASR 厂商无关边界；输入始终是单个音频切片。 */
public interface TranscriptionProvider {
  TranscriptionResult transcribe(Path audioChunk, TranscriptionRequest request);

  record TranscriptionRequest(long chunkStartMs, String language) {}

  record TranscriptionSegment(long startMs, long endMs, String text) {}

  record TranscriptionResult(List<TranscriptionSegment> segments, String modelName) {
    public TranscriptionResult {
      segments = List.copyOf(segments);
    }
  }
}
