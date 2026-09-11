package com.shangan.diagnostics.infrastructure;

import com.shangan.diagnostics.domain.DiagnosticLogUpload;
import java.util.List;
import java.util.Optional;

/** 诊断日志台账持久化；文件内容由应用服务写入磁盘。 */
public interface DiagnosticLogRepository {

  void insert(DiagnosticLogUpload upload);

  Optional<DiagnosticLogUpload> findById(String id);

  List<DiagnosticLogUpload> listRecent(int limit);

  List<DiagnosticLogUpload> listByUserOldestFirst(String userId);

  void delete(String id);

  /** 后台列表用的用户名，避免把 users 表泄漏给 diagnostics 以外的查询。 */
  Optional<String> usernameOf(String userId);
}
