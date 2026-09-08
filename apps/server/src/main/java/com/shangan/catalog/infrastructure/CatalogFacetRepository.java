package com.shangan.catalog.infrastructure;

import java.util.List;

/** 课程库筛选维度的聚合查询边界；维度全部来自 Emby 元数据投影。 */
public interface CatalogFacetRepository {

  /** 可见课程的流派聚合。 */
  List<FacetValue> genres();

  /** 可见课程的标签聚合。 */
  List<FacetValue> tags();

  /** 可见课程的人物聚合。 */
  List<FacetValue> people();

  /** 可见课程的年份聚合。 */
  List<FacetValue> years();

  /** 按维度筛选出的课程 ID；空条件表示不限制。 */
  List<String> courseIdsMatching(
      String genre, String tag, String person, Integer year, String query);

  /** 一个筛选维度的取值与课程计数。 */
  record FacetValue(String value, int courseCount) {}
}
