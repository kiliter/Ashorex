package com.shangan.diagnostics.domain;

import java.time.Instant;

/** 一次用户手动上报的诊断日志台账，不含磁盘绝对路径。 */
public record DiagnosticLogUpload(
    String id,
    String userId,
    String storagePath,
    long sizeBytes,
    String appVersion,
    String platform,
    Instant uploadedAt) {}
