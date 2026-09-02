package com.shangan.catalog.infrastructure;

import com.shangan.catalog.domain.CourseDeletionGraph;
import java.util.List;

/** 课程完整关联图的统计与物理删除边界。 */
public interface CourseDeletionRepository {

  CourseDeletionGraph.Impact inspect(List<String> courseIds);

  CourseDeletionGraph.DeletionResult deleteGraph(List<String> courseIds);
}
