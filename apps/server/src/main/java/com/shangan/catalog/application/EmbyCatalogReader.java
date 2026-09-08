package com.shangan.catalog.application;

import com.shangan.catalog.domain.ResourceMetadata;
import com.shangan.catalog.domain.ResourceType;
import com.shangan.media.emby.EmbyDtos;
import com.shangan.media.emby.EmbyGateway;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 把 Emby 返回的安全 DTO 转换为内部统一元数据结构。
 *
 * <p>按 ADR-0030，元数据来源只有 Emby，因此这里不是多态适配层，只是一次字段映射。 视频与书籍共用同一路径，仅 {@code resourceType} 与总量字段不同。
 */
@Component
public class EmbyCatalogReader {

  private final EmbyGateway emby;

  public EmbyCatalogReader(EmbyGateway emby) {
    this.emby = emby;
  }

  /** 读取课程级元数据快照，用于整表重写三张只读投影。 */
  public ResourceMetadata.CourseMetadata readCourseMetadata(String parentExternalRef) {
    EmbyDtos.SourceMetadata source = emby.getSourceMetadata(parentExternalRef);
    List<ResourceMetadata.Person> people = new ArrayList<>();
    for (EmbyDtos.PersonRef person : source.people()) {
      people.add(new ResourceMetadata.Person(person.name(), person.role()));
    }
    return new ResourceMetadata.CourseMetadata(
        source.id(),
        source.name(),
        source.overview(),
        source.productionYear(),
        source.genres(),
        source.tags(),
        List.copyOf(people));
  }

  /**
   * 读取课程下的资源快照。
   *
   * <p>任一页失败时 {@link EmbyGateway} 会抛出稳定业务错误，调用方据此放弃整次同步， 不写入部分快照（Spec 12.2）。
   *
   * <p>课时序号（{@code sortIndex}）由 {@link EmbyGateway#listChildren} 定稿：优先远端 {@code IndexNumber}，
   * 缺失时才回退到远端列表位置（见 ADR-0034）。这里的 {@code fallbackIndex} 只是兜底，防止 纯逻辑测试用的 Fake 网关返回 0；生产路径不会走到。
   */
  public List<ResourceMetadata> readResources(String parentExternalRef) {
    List<ResourceMetadata> result = new ArrayList<>();
    int fallbackIndex = 0;
    for (EmbyDtos.MediaItem item : emby.listChildren(parentExternalRef)) {
      fallbackIndex++;
      boolean book = "Book".equalsIgnoreCase(item.itemType());
      result.add(
          new ResourceMetadata(
              item.id(),
              item.title(),
              item.indexNumber() > 0 ? item.indexNumber() : fallbackIndex,
              book ? null : item.durationMs(),
              null,
              item.sourceFingerprint(),
              book ? ResourceType.DOCUMENT : ResourceType.VIDEO));
    }
    return List.copyOf(result);
  }
}
