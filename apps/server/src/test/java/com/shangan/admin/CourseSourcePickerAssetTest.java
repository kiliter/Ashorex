package com.shangan.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 验证课程创建和重新映射页面都使用“按钮打开、弹窗选择”的统一交互契约。 */
class CourseSourcePickerAssetTest {

  @Test
  void coursePagesUseTheModalPickerInsteadOfARequiredInlineSearch() throws IOException {
    String courses = resourceText("templates/admin/courses.html");
    String rebind = resourceText("templates/admin/course-source.html");

    assertThat(courses)
        .contains("data-source-open", "data-source-modal", "data-source-confirm")
        .contains("选择 Emby 课程", "确定绑定")
        .doesNotContain("source-combobox");
    assertThat(rebind)
        .contains("data-mode=\"single\"")
        .contains("data-source-open", "data-source-modal", "data-source-confirm")
        .doesNotContain("source-combobox");
  }

  @Test
  void pickerLoadsAllSourcesOnlyAfterOpeningAndFiltersThemLocally() throws IOException {
    String script = resourceText("static/assets/course-source-picker.js");

    assertThat(script)
        .contains("openButton.addEventListener(\"click\", openModal)")
        .contains("url.searchParams.set(\"query\", \"\")")
        .contains("const visibleCandidates")
        .contains("draftSelected = new Map(selected)")
        .contains("confirmButton.addEventListener")
        .contains("field.setAttribute(\"value\", source.id)")
        .contains("selected.size !== 1 && !manualId")
        .contains("MAX_MULTIPLE_SOURCES = 50");
  }

  /** 使用 UTF-8 读取已打包的后台静态资源，防止模板与脚本改动后契约漂移。 */
  private String resourceText(String path) throws IOException {
    return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
  }
}
