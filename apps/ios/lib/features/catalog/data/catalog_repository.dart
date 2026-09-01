import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shangan_ios/core/api/api_client.dart';

/// 课程列表中的本地服务端快照。
final class CourseSummary {
  const CourseSummary({
    required this.id,
    required this.name,
    required this.description,
  });

  final String id;
  final String name;
  final String description;

  factory CourseSummary.fromJson(Map<String, dynamic> json) => CourseSummary(
    id: json['id'] as String,
    name: json['name'] as String,
    description: json['description'] as String? ?? '',
  );
}

/// 可选择进入学习或计划的课时。
final class LessonSummary {
  const LessonSummary({
    required this.id,
    required this.courseId,
    required this.title,
    required this.durationMs,
    required this.sortOrder,
    required this.maxVerifiedPositionMs,
    required this.progressPercent,
    required this.learningStatus,
    required this.summaryAvailable,
  });

  final String id;
  final String courseId;
  final String title;
  final int durationMs;
  final int sortOrder;
  final int maxVerifiedPositionMs;
  final int progressPercent;
  final String learningStatus;
  final bool summaryAvailable;

  factory LessonSummary.fromJson(Map<String, dynamic> json) => LessonSummary(
    id: json['id'] as String,
    courseId: json['courseId'] as String,
    title: json['title'] as String,
    durationMs: (json['durationMs'] as num).toInt(),
    sortOrder: (json['sortOrder'] as num).toInt(),
    maxVerifiedPositionMs:
        (json['maxVerifiedPositionMs'] as num?)?.toInt() ?? 0,
    progressPercent: (json['progressPercent'] as num?)?.toInt() ?? 0,
    learningStatus: json['learningStatus'] as String? ?? 'NOT_STARTED',
    summaryAvailable: json['summaryAvailable'] as bool? ?? false,
  );
}

/// 课时学习内容按需读取，课程列表不携带可能很长的全文或摘要。
final class LessonStudyContentData {
  const LessonStudyContentData({
    required this.summaryStatus,
    required this.summaryMarkdown,
  });

  final String summaryStatus;
  final String? summaryMarkdown;

  factory LessonStudyContentData.fromJson(Map<String, dynamic> json) =>
      LessonStudyContentData(
        summaryStatus: json['summaryStatus'] as String,
        summaryMarkdown: json['summaryMarkdown'] as String?,
      );
}

final class CourseDetail {
  const CourseDetail({
    required this.id,
    required this.name,
    required this.description,
    required this.lessons,
  });

  final String id;
  final String name;
  final String description;
  final List<LessonSummary> lessons;

  factory CourseDetail.fromJson(Map<String, dynamic> json) => CourseDetail(
    id: json['id'] as String,
    name: json['name'] as String,
    description: json['description'] as String? ?? '',
    lessons: (json['lessons'] as List<dynamic>)
        .map(
          (item) =>
              LessonSummary.fromJson(Map<String, dynamic>.from(item as Map)),
        )
        .toList(),
  );
}

abstract interface class CatalogRepository {
  Future<List<CourseSummary>> listCourses();

  Future<CourseDetail> loadCourse(String courseId);

  /// 进入播放器时只读取课时元数据，不创建观看会话或改变作战单状态。
  Future<LessonSummary> loadLesson(String lessonId);

  Future<LessonStudyContentData> loadStudyContent(String lessonId);
}

/// 课程页面只读取上岸服务端快照，从不直接连接 Emby。
final class RemoteCatalogRepository implements CatalogRepository {
  RemoteCatalogRepository(this._api);

  final ApiClient _api;

  @override
  Future<List<CourseSummary>> listCourses() async {
    return (await _api.getJsonList(
      '/api/v1/courses',
    )).map(CourseSummary.fromJson).toList();
  }

  @override
  Future<CourseDetail> loadCourse(String courseId) async {
    return CourseDetail.fromJson(
      await _api.getJson('/api/v1/courses/$courseId'),
    );
  }

  @override
  Future<LessonSummary> loadLesson(String lessonId) async =>
      LessonSummary.fromJson(await _api.getJson('/api/v1/lessons/$lessonId'));

  @override
  Future<LessonStudyContentData> loadStudyContent(String lessonId) async =>
      LessonStudyContentData.fromJson(
        await _api.getJson('/api/v1/lessons/$lessonId/study-content'),
      );
}

final catalogRepositoryProvider = Provider<CatalogRepository>((ref) {
  throw StateError('CatalogRepository 尚未注入');
});
