package com.shangan.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shangan.common.api.BusinessException;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import com.shangan.media.emby.EmbyDtos;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证媒体库绑定只接受当前用户可见媒体库和三个明确的内容类型。 */
class EmbyLibraryBindingValidatorTest {

  private final EmbyLibraryBindingValidator validator = new EmbyLibraryBindingValidator();
  private final List<EmbyDtos.MediaLibrary> available =
      List.of(
          new EmbyDtos.MediaLibrary("library-1", "剧集库", "tvshows"),
          new EmbyDtos.MediaLibrary("library-2", "混合库", "mixed"));

  @Test
  void resolvesMultipleVisibleLibrariesInSubmissionOrder() {
    assertThat(validator.validate(available, List.of("library-1|SERIES", "library-2|MIXED")))
        .containsExactly(
            new RuntimeIntegrationSettings.EmbyLibrary(
                "library-1", "剧集库", RuntimeIntegrationSettings.EmbyLibraryType.SERIES),
            new RuntimeIntegrationSettings.EmbyLibrary(
                "library-2", "混合库", RuntimeIntegrationSettings.EmbyLibraryType.MIXED));
  }

  @Test
  void rejectsInvisibleLibraryAndUnknownType() {
    assertThatThrownBy(() -> validator.validate(available, List.of("missing|MOVIE")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo("EMBY_LIBRARY_CHANGED"));
    assertThatThrownBy(() -> validator.validate(available, List.of("library-1|FOLDER")))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception -> assertThat(exception.errorCode()).isEqualTo("EMBY_LIBRARY_TYPE_INVALID"));
  }
}
