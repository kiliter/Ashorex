package com.shangan.catalog.infrastructure;

import com.shangan.common.api.BusinessException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** 在内存上限内解析课程学习内容 ZIP，不把任何上传条目写入磁盘。 */
@Component
public class LessonStudyContentZipParser {

  static final long MAX_COMPRESSED_BYTES = 50L * 1024L * 1024L;
  static final long MAX_EXPANDED_BYTES = 100L * 1024L * 1024L;

  private final ObjectMapper objectMapper;
  private final long maxCompressedBytes;
  private final long maxExpandedBytes;

  /** 使用冻结设计规定的 50 MiB 压缩包和 100 MiB 解压文本上限。 */
  @Autowired
  public LessonStudyContentZipParser(ObjectMapper objectMapper) {
    this(objectMapper, MAX_COMPRESSED_BYTES, MAX_EXPANDED_BYTES);
  }

  /** 允许测试缩小大小上限，以低成本覆盖边界行为。 */
  LessonStudyContentZipParser(
      ObjectMapper objectMapper, long maxCompressedBytes, long maxExpandedBytes) {
    this.objectMapper =
        objectMapper.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    this.maxCompressedBytes = maxCompressedBytes;
    this.maxExpandedBytes = maxExpandedBytes;
  }

  /**
   * 解析并完整校验 ZIP 固定结构。
   *
   * @param zipBytes 上传的压缩包字节
   * @return 按 manifest 顺序排列的课程内容
   */
  public ParsedPackage parse(byte[] zipBytes) {
    if (zipBytes == null || zipBytes.length == 0) {
      throw invalid("上传的 ZIP 不能为空");
    }
    if (zipBytes.length > maxCompressedBytes) {
      throw invalid("上传的 ZIP 不能超过 50 MiB");
    }

    Map<String, byte[]> entries = readEntries(zipBytes);
    byte[] manifestBytes = entries.get("manifest.json");
    if (manifestBytes == null) {
      throw invalid("ZIP 根目录缺少 manifest.json");
    }

    Manifest manifest = readManifest(manifestBytes);
    if (manifest.version() != 1) {
      throw invalid("manifest.json 的 version 必须为 1");
    }
    if (manifest.lessons() == null || manifest.lessons().isEmpty()) {
      throw invalid("manifest.json 至少需要包含一个课时");
    }

    Set<String> embyItemIds = new HashSet<>();
    List<String> orderedEmbyItemIds = new ArrayList<>(manifest.lessons().size());
    for (ManifestLesson lesson : manifest.lessons()) {
      String embyItemId = normalizeEmbyItemId(lesson == null ? null : lesson.embyItemId());
      if (!embyItemIds.add(embyItemId)) {
        throw invalid("manifest.json 包含重复的 Emby Item ID：" + embyItemId);
      }
      orderedEmbyItemIds.add(embyItemId);
    }

    List<ParsedLesson> lessons = new ArrayList<>(orderedEmbyItemIds.size());
    for (String embyItemId : orderedEmbyItemIds) {
      String lessonRoot = "lessons/" + embyItemId + "/";
      String transcript =
          requiredUtf8(entries, lessonRoot + "transcript.txt", embyItemId, "transcript.txt");
      String summary = requiredUtf8(entries, lessonRoot + "summary.md", embyItemId, "summary.md");
      lessons.add(new ParsedLesson(embyItemId, transcript, summary));
    }
    return new ParsedPackage(List.copyOf(lessons));
  }

  /** 逐条读取 ZIP，并对路径、重复条目和累计解压大小做统一校验。 */
  private Map<String, byte[]> readEntries(byte[] zipBytes) {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    long expandedBytes = 0;
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        String name = entry.getName();
        validateEntryPath(name);
        if (entry.isDirectory()) {
          zip.closeEntry();
          continue;
        }
        if (entries.containsKey(name)) {
          throw invalid("ZIP 包含重复文件：" + name);
        }
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = zip.read(buffer)) != -1) {
          expandedBytes += read;
          if (expandedBytes > maxExpandedBytes) {
            throw invalid("ZIP 累计解压文本不能超过 100 MiB");
          }
          content.write(buffer, 0, read);
        }
        entries.put(name, content.toByteArray());
        zip.closeEntry();
      }
    } catch (BusinessException exception) {
      throw exception;
    } catch (IOException exception) {
      throw invalid("ZIP 文件损坏或无法读取");
    }
    return entries;
  }

  /** 拒绝绝对路径、Windows 分隔符和任何相对路径穿越片段。 */
  private void validateEntryPath(String name) {
    if (name == null || name.isBlank() || name.startsWith("/") || name.contains("\\")) {
      throw invalid("ZIP 包含非法路径");
    }
    for (String segment : name.split("/", -1)) {
      if (segment.equals("..") || segment.equals(".")) {
        throw invalid("ZIP 包含非法路径：" + name);
      }
    }
  }

  /** 严格读取 manifest，未知字段和 JSON 语法错误均视为整包无效。 */
  private Manifest readManifest(byte[] manifestBytes) {
    try {
      return objectMapper.readValue(manifestBytes, Manifest.class);
    } catch (JacksonException exception) {
      throw invalid("manifest.json 格式不正确");
    }
  }

  /** 校验 Emby Item ID 非空且不会改变固定目录层级。 */
  private String normalizeEmbyItemId(String value) {
    if (value == null || value.isBlank()) {
      throw invalid("manifest.json 中的 Emby Item ID 不能为空");
    }
    String normalized = value.trim();
    if (normalized.contains("/") || normalized.contains("\\") || normalized.equals("..")) {
      throw invalid("manifest.json 包含非法 Emby Item ID：" + normalized);
    }
    return normalized;
  }

  /** 读取每集必需文件，并使用报告模式严格校验 UTF-8 与非空内容。 */
  private String requiredUtf8(
      Map<String, byte[]> entries, String path, String embyItemId, String fileName) {
    byte[] bytes = entries.get(path);
    if (bytes == null) {
      throw invalid("Emby Item ID " + embyItemId + " 缺少 " + fileName);
    }
    String value;
    try {
      value =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString()
              .trim();
    } catch (CharacterCodingException exception) {
      throw invalid("Emby Item ID " + embyItemId + " 的 " + fileName + " 不是有效 UTF-8");
    }
    if (value.isEmpty()) {
      throw invalid("Emby Item ID " + embyItemId + " 的 " + fileName + " 不能为空");
    }
    return value;
  }

  /** 创建可安全返回管理页面的统一导入错误。 */
  private BusinessException invalid(String message) {
    return new BusinessException(HttpStatus.BAD_REQUEST, "STUDY_CONTENT_IMPORT_INVALID", message);
  }

  /** 完整校验后的 ZIP 内容。 */
  public record ParsedPackage(List<ParsedLesson> lessons) {}

  /** 单集完整全文和 Markdown 摘要。 */
  public record ParsedLesson(String embyItemId, String fullText, String summaryMarkdown) {}

  /** manifest 根对象，只接受固定版本和课时数组。 */
  private record Manifest(int version, List<ManifestLesson> lessons) {}

  /** manifest 中只允许声明用于精确匹配的 Emby Item ID。 */
  private record ManifestLesson(String embyItemId) {}
}
