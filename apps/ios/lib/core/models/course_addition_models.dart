/// 课程添加预览的历史选项；展示业务名称与日期，不展示内部 ID。
final class HistoryCourseTodo {
  const HistoryCourseTodo({
    required this.id,
    required this.localDate,
    required this.title,
    required this.targetProgressPermille,
    required this.progressPositionMs,
  });
  factory HistoryCourseTodo.fromJson(Map<String, dynamic> json) =>
      HistoryCourseTodo(
        id: json['id'] as String,
        localDate: json['localDate'] as String,
        title: json['title'] as String,
        targetProgressPermille: (json['targetProgressPermille'] as num).toInt(),
        progressPositionMs: (json['progressPositionMs'] as num).toInt(),
      );
  final String id;
  final String localDate;
  final String title;
  final int targetProgressPermille;
  final int progressPositionMs;
}

/// 每个课时的判定来自服务端，客户端不自行猜测历史冲突。
final class CourseAdditionItem {
  const CourseAdditionItem({
    required this.resourceId,
    required this.title,
    required this.status,
    required this.history,
  });
  factory CourseAdditionItem.fromJson(Map<String, dynamic> json) =>
      CourseAdditionItem(
        resourceId: json['resourceId'] as String,
        title: json['title'] as String,
        status: json['status'] as String,
        history: (json['history'] as List<dynamic>)
            .map(
              (item) =>
                  HistoryCourseTodo.fromJson(item as Map<String, dynamic>),
            )
            .toList(),
      );
  final String resourceId;
  final String title;
  final String status;
  final List<HistoryCourseTodo> history;
}

/// 汇总采用实际提交结果，提交前的预览数量不能代替最终状态。
final class CourseAdditionResult {
  const CourseAdditionResult(
    this.created,
    this.deferred,
    this.reused,
    this.skipped,
  );
  factory CourseAdditionResult.fromJson(Map<String, dynamic> json) =>
      CourseAdditionResult(
        (json['created'] as num).toInt(),
        (json['deferred'] as num).toInt(),
        (json['reused'] as num).toInt(),
        (json['skipped'] as num).toInt(),
      );
  final int created;
  final int deferred;
  final int reused;
  final int skipped;
  String get message =>
      '新增 $created 个课时，顺延 $deferred 个，复用 $reused 个，跳过 $skipped 个';
}
