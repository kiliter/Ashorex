package com.shangan.catalog.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shangan.common.api.BusinessException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** 验证课程学习内容 ZIP 的固定结构、UTF-8 和大小边界。 */
class LessonStudyContentZipParserTest {

  private final LessonStudyContentZipParser parser =
      new LessonStudyContentZipParser(new ObjectMapper(), 1024 * 1024, 1024 * 1024);

  /** 正确包应按 manifest 顺序返回去除首尾空白后的全文和摘要。 */
  @Test
  void parsesCompletePackage() throws IOException {
    byte[] zip =
        zip(
            Map.of(
                "manifest.json",
                utf8(
                    """
                    {"version":1,"lessons":[{"embyItemId":"emby-1"}]}
                    """),
                "lessons/emby-1/transcript.txt",
                utf8("  第一段\n第二段  "),
                "lessons/emby-1/summary.md",
                utf8("  # 摘要  ")));

    LessonStudyContentZipParser.ParsedPackage parsed = parser.parse(zip);

    assertThat(parsed.lessons()).hasSize(1);
    assertThat(parsed.lessons().getFirst().embyItemId()).isEqualTo("emby-1");
    assertThat(parsed.lessons().getFirst().fullText()).isEqualTo("第一段\n第二段");
    assertThat(parsed.lessons().getFirst().summaryMarkdown()).isEqualTo("# 摘要");
  }

  /** 缺文件、重复 ID 和非法路径都必须返回稳定导入错误码。 */
  @Test
  void rejectsIncompleteDuplicateAndTraversalPackages() throws IOException {
    byte[] missingSummary =
        zip(
            Map.of(
                "manifest.json",
                utf8("{\"version\":1,\"lessons\":[{\"embyItemId\":\"emby-1\"}]}"),
                "lessons/emby-1/transcript.txt",
                utf8("全文")));
    byte[] duplicateId =
        zip(
            Map.of(
                "manifest.json",
                utf8(
                    "{\"version\":1,\"lessons\":[{\"embyItemId\":\"emby-1\"},{\"embyItemId\":\"emby-1\"}]}")));
    byte[] traversal =
        zip(
            Map.of(
                "manifest.json",
                utf8("{\"version\":1,\"lessons\":[]}"),
                "../secret.txt",
                utf8("不能读取")));

    assertImportError(missingSummary, "summary.md");
    assertImportError(duplicateId, "重复");
    assertImportError(traversal, "非法路径");
  }

  /** 非法 UTF-8、空文本和超出任一大小上限的包必须整包拒绝。 */
  @Test
  void rejectsInvalidTextAndSizeLimits() throws IOException {
    Map<String, byte[]> invalidEntries = validEntries();
    invalidEntries.put("lessons/emby-1/transcript.txt", new byte[] {(byte) 0xC3, (byte) 0x28});
    assertImportError(zip(invalidEntries), "UTF-8");

    Map<String, byte[]> emptyEntries = validEntries();
    emptyEntries.put("lessons/emby-1/summary.md", utf8("   \n"));
    assertImportError(zip(emptyEntries), "不能为空");

    byte[] validZip = zip(validEntries());
    LessonStudyContentZipParser compressedLimited =
        new LessonStudyContentZipParser(new ObjectMapper(), validZip.length - 1L, 1024 * 1024);
    assertThatThrownBy(() -> compressedLimited.parse(validZip))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.errorCode()).isEqualTo("STUDY_CONTENT_IMPORT_INVALID");
              assertThat(error.getMessage()).contains("50 MiB");
            });

    LessonStudyContentZipParser expandedLimited =
        new LessonStudyContentZipParser(new ObjectMapper(), 1024 * 1024, 10);
    assertThatThrownBy(() -> expandedLimited.parse(validZip))
        .isInstanceOfSatisfying(
            BusinessException.class, error -> assertThat(error.getMessage()).contains("100 MiB"));
  }

  /** 断言解析失败时同时校验稳定错误码和可修复中文原因。 */
  private void assertImportError(byte[] zip, String messagePart) {
    assertThatThrownBy(() -> parser.parse(zip))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.errorCode()).isEqualTo("STUDY_CONTENT_IMPORT_INVALID");
              assertThat(error.getMessage()).contains(messagePart);
            });
  }

  /** 构造一份最小合法包的有序条目，测试可按需替换其中内容。 */
  private Map<String, byte[]> validEntries() {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("manifest.json", utf8("{\"version\":1,\"lessons\":[{\"embyItemId\":\"emby-1\"}]}"));
    entries.put("lessons/emby-1/transcript.txt", utf8("完整全文"));
    entries.put("lessons/emby-1/summary.md", utf8("# 摘要"));
    return entries;
  }

  /** 将字符串编码为 UTF-8 测试条目。 */
  private byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  /** 在内存中创建 ZIP 测试夹具，不在工作区留下临时文件。 */
  private byte[] zip(Map<String, byte[]> entries) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
    }
    return bytes.toByteArray();
  }
}
