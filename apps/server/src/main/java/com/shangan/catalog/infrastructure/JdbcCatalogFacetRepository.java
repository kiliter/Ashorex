package com.shangan.catalog.infrastructure;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 筛选维度聚合的 SQLite 实现；只统计学习端可见课程。 */
@Repository
public class JdbcCatalogFacetRepository implements CatalogFacetRepository {

  private static final String VISIBLE = " c.status = 'ACTIVE' AND c.source_missing = 0 ";

  private final JdbcClient jdbcClient;

  public JdbcCatalogFacetRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  @Override
  public List<FacetValue> genres() {
    return jdbcClient
        .sql(
            """
            SELECT g.genre AS value, count(*) AS course_count
              FROM course_genres g
              JOIN courses c ON c.id = g.course_id
             WHERE
            """
                + VISIBLE
                + " GROUP BY g.genre ORDER BY course_count DESC, g.genre")
        .query(this::mapFacet)
        .list();
  }

  @Override
  public List<FacetValue> tags() {
    return jdbcClient
        .sql(
            """
            SELECT t.tag AS value, count(*) AS course_count
              FROM course_tags t
              JOIN courses c ON c.id = t.course_id
             WHERE
            """
                + VISIBLE
                + " GROUP BY t.tag ORDER BY course_count DESC, t.tag")
        .query(this::mapFacet)
        .list();
  }

  @Override
  public List<FacetValue> people() {
    return jdbcClient
        .sql(
            """
            SELECT p.person_name AS value, count(*) AS course_count
              FROM course_people p
              JOIN courses c ON c.id = p.course_id
             WHERE
            """
                + VISIBLE
                + " GROUP BY p.person_name ORDER BY course_count DESC, p.person_name")
        .query(this::mapFacet)
        .list();
  }

  @Override
  public List<FacetValue> years() {
    return jdbcClient
        .sql(
            """
            SELECT CAST(c.production_year AS TEXT) AS value, count(*) AS course_count
              FROM courses c
             WHERE
            """
                + VISIBLE
                + " AND c.production_year IS NOT NULL GROUP BY c.production_year ORDER BY c.production_year DESC")
        .query(this::mapFacet)
        .list();
  }

  @Override
  public List<String> courseIdsMatching(
      String genre, String tag, String person, Integer year, String query) {
    StringBuilder sql = new StringBuilder("SELECT c.id FROM courses c WHERE ").append(VISIBLE);
    List<String> conditions = new ArrayList<>();
    if (notBlank(genre)) {
      conditions.add(
          "EXISTS (SELECT 1 FROM course_genres g WHERE g.course_id = c.id AND g.genre = :genre)");
    }
    if (notBlank(tag)) {
      conditions.add(
          "EXISTS (SELECT 1 FROM course_tags t WHERE t.course_id = c.id AND t.tag = :tag)");
    }
    if (notBlank(person)) {
      conditions.add(
          """
          EXISTS (SELECT 1 FROM course_people p
                   WHERE p.course_id = c.id AND p.person_name = :person)
          """);
    }
    if (year != null) {
      conditions.add("c.production_year = :year");
    }
    if (notBlank(query)) {
      conditions.add(
          """
          (LOWER(c.title) LIKE :query
           OR EXISTS (SELECT 1 FROM course_people p
                       WHERE p.course_id = c.id AND LOWER(p.person_name) LIKE :query))
          """);
    }
    for (String condition : conditions) {
      sql.append(" AND ").append(condition);
    }
    sql.append(" ORDER BY c.sort_order, c.title");

    var statement = jdbcClient.sql(sql.toString());
    if (notBlank(genre)) {
      statement = statement.param("genre", genre.trim());
    }
    if (notBlank(tag)) {
      statement = statement.param("tag", tag.trim());
    }
    if (notBlank(person)) {
      statement = statement.param("person", person.trim());
    }
    if (year != null) {
      statement = statement.param("year", year);
    }
    if (notBlank(query)) {
      statement =
          statement.param("query", "%" + query.trim().toLowerCase(java.util.Locale.ROOT) + "%");
    }
    return statement.query(String.class).list();
  }

  private boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private FacetValue mapFacet(java.sql.ResultSet row, int rowNumber) throws java.sql.SQLException {
    return new FacetValue(row.getString("value"), row.getInt("course_count"));
  }
}
