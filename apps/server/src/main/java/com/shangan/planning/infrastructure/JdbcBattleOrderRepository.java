package com.shangan.planning.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 使用单条版本条件更新保证多个页面不会无声覆盖同一份作战单。 */
@Repository
public class JdbcBattleOrderRepository implements BattleOrderRepository {
  private final JdbcClient jdbc;

  public JdbcBattleOrderRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<PlanRow> findPlan(String userId, LocalDate date) {
    return jdbc.sql(
            "select id,user_id,plan_date,lifecycle_status,version from daily_plans "
                + "where user_id=:userId and plan_date=:date")
        .params(Map.of("userId", userId, "date", date.toString()))
        .query(
            (rs, row) ->
                new PlanRow(
                    rs.getString("id"),
                    rs.getString("user_id"),
                    LocalDate.parse(rs.getString("plan_date")),
                    rs.getString("lifecycle_status"),
                    rs.getLong("version")))
        .optional();
  }

  @Override
  public List<PlanDayRow> listPlanDays(String userId, LocalDate from, LocalDate to) {
    return jdbc.sql(
            """
            select p.plan_date, p.lifecycle_status,
              count(i.id) as item_count,
              coalesce(sum(case when i.status='COMPLETED' then 1 else 0 end), 0) as completed_item_count,
              coalesce(sum(i.planned_seconds), 0) as planned_seconds
            from daily_plans p
            left join daily_plan_items i on i.plan_id=p.id
            where p.user_id=:userId and p.plan_date>=:fromDate and p.plan_date<=:toDate
            group by p.id, p.plan_date, p.lifecycle_status
            order by p.plan_date
            """)
        .param("userId", userId)
        .param("fromDate", from.toString())
        .param("toDate", to.toString())
        .query(
            (rs, row) ->
                new PlanDayRow(
                    LocalDate.parse(rs.getString("plan_date")),
                    rs.getString("lifecycle_status"),
                    rs.getInt("item_count"),
                    rs.getInt("completed_item_count"),
                    rs.getLong("planned_seconds")))
        .list();
  }

  @Override
  public List<ItemRow> findItems(String planId) {
    return jdbc.sql(
            """
            select i.*,
              m.course_id as course_id,
              c.name as course_name,
              s.status as mock_exam_session_status,
              case when i.status <> 'PENDING' or i.completed_seconds > 0
                or exists(select 1 from watch_sessions w where w.plan_item_id=i.id)
                or exists(select 1 from mock_exam_sessions mx where mx.plan_item_id=i.id)
              then 1 else 0 end as immutable_item
            from daily_plan_items i
            left join media_items m on m.id=i.media_item_id
            left join courses c on c.id=m.course_id
            left join mock_exam_sessions s on s.plan_item_id=i.id
            where i.plan_id=:planId
            order by i.sort_order,i.id
            """)
        .param("planId", planId)
        .query(this::mapItem)
        .list();
  }

  @Override
  public void insertPlan(String id, String userId, LocalDate date, Instant now) {
    jdbc.sql(
            """
            insert into daily_plans (
              id,user_id,plan_date,status,lifecycle_status,version,created_at,updated_at
            ) values (:id,:userId,:date,'DRAFT','DRAFT',0,:now,:now)
            """)
        .param("id", id)
        .param("userId", userId)
        .param("date", date.toString())
        .param("now", now.toEpochMilli())
        .update();
  }

  @Override
  public boolean activateAndIncrement(String planId, long expectedVersion, Instant now) {
    return jdbc.sql(
                """
                update daily_plans
                set status='LOCKED', lifecycle_status='ACTIVE', version=version+1,
                    locked_at=coalesce(locked_at,:now), activated_at=coalesce(activated_at,:now),
                    updated_at=:now
                where id=:id and version=:expected and lifecycle_status in ('DRAFT','ACTIVE')
                """)
            .param("now", now.toEpochMilli())
            .param("id", planId)
            .param("expected", expectedVersion)
            .update()
        == 1;
  }

  @Override
  public void deleteMutableItems(String planId) {
    jdbc.sql(
            """
            delete from daily_plan_items
            where plan_id=:planId and status='PENDING' and completed_seconds=0
              and not exists(select 1 from watch_sessions w where w.plan_item_id=daily_plan_items.id)
              and not exists(select 1 from mock_exam_sessions m where m.plan_item_id=daily_plan_items.id)
            """)
        .param("planId", planId)
        .update();
  }

  @Override
  public void insertItem(ItemDraft item, Instant now) {
    jdbc.sql(
            """
            insert into daily_plan_items (
              id,plan_id,item_type,item_kind,title,media_item_id,debt_id,
              mock_exam_preset_id,mock_exam_name_snapshot,
              planned_seconds,completed_seconds,watch_completed,quiz_required,quiz_completed,
              status,sort_order,created_at,updated_at
            ) values (
              :id,:planId,:physicalType,:logicalType,:title,:mediaId,null,
              :presetId,:examName,
              :planned,0,0,:quizRequired,0,'PENDING',:sortOrder,:now,:now
            )
            """)
        .param("id", item.id())
        .param("planId", item.planId())
        .param("physicalType", item.physicalType())
        .param("logicalType", item.logicalType())
        .param("title", item.title())
        .param("mediaId", item.mediaItemId())
        .param("presetId", item.mockExamPresetId())
        .param("examName", item.mockExamNameSnapshot())
        .param("planned", item.plannedSeconds())
        .param("quizRequired", item.quizRequired() ? 1 : 0)
        .param("sortOrder", item.sortOrder())
        .param("now", now.toEpochMilli())
        .update();
  }

  @Override
  public void updateItemSortOrder(String planId, String itemId, int sortOrder, Instant now) {
    jdbc.sql(
            "update daily_plan_items set sort_order=:sortOrder,updated_at=:now "
                + "where id=:itemId and plan_id=:planId")
        .param("sortOrder", sortOrder)
        .param("now", now.toEpochMilli())
        .param("itemId", itemId)
        .param("planId", planId)
        .update();
  }

  @Override
  public void insertRevision(
      String id, String planId, long version, String snapshotJson, Instant now) {
    jdbc.sql(
            "insert into daily_plan_revisions "
                + "(id,plan_id,version,items_snapshot_json,created_at) "
                + "values (:id,:planId,:version,:snapshot,:now)")
        .param("id", id)
        .param("planId", planId)
        .param("version", version)
        .param("snapshot", snapshotJson)
        .param("now", now.toEpochMilli())
        .update();
  }

  @Override
  public boolean isLessonCompleted(String userId, String mediaItemId) {
    return jdbc.sql(
                "select count(*) from video_progress "
                    + "where user_id=:userId and media_item_id=:mediaId and completed_at is not null")
            .params(Map.of("userId", userId, "mediaId", mediaItemId))
            .query(Integer.class)
            .single()
        > 0;
  }

  private ItemRow mapItem(ResultSet rs, int row) throws SQLException {
    return new ItemRow(
        rs.getString("id"),
        rs.getString("plan_id"),
        rs.getString("item_kind") == null ? rs.getString("item_type") : rs.getString("item_kind"),
        rs.getString("title"),
        rs.getString("media_item_id"),
        rs.getString("mock_exam_preset_id"),
        rs.getString("mock_exam_name_snapshot"),
        rs.getLong("planned_seconds"),
        rs.getLong("completed_seconds"),
        rs.getString("status"),
        rs.getInt("sort_order"),
        rs.getInt("immutable_item") == 1,
        rs.getString("course_id"),
        rs.getString("course_name"),
        rs.getInt("quiz_required") == 1,
        rs.getString("debt_id"),
        rs.getString("mock_exam_session_status"));
  }
}
