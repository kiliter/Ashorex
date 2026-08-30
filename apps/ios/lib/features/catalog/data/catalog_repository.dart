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
  });

  final String id;
  final String courseId;
  final String title;
  final int durationMs;
  final int sortOrder;

  factory LessonSummary.fromJson(Map<String, dynamic> json) => LessonSummary(
    id: json['id'] as String,
    courseId: json['courseId'] as String,
    title: json['title'] as String,
    durationMs: (json['durationMs'] as num).toInt(),
    sortOrder: (json['sortOrder'] as num).toInt(),
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
}

/// 课程页面只读取上岸服务端快照，从不直接连接 Emby。
final class RemoteCatalogRepository implements CatalogRepository {
  RemoteCatalogRepository(this._api);

  final ApiClient _api;

  @override
  Future<List<CourseSummary>> listCourses() async {
    return (await _api.getJsonList('/api/v1/courses'))
        .map(CourseSummary.fromJson)
        .toList();
  }

  @override
  Future<CourseDetail> loadCourse(String courseId) async {
    return CourseDetail.fromJson(
      await _api.getJson('/api/v1/courses/$courseId'),
    );
  }
}

final catalogRepositoryProvider = Provider<CatalogRepository>((ref) {
  throw StateError('CatalogRepository 尚未注入');
});
