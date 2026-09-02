package com.shangan.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.shangan.ai.content.application.OpenRouterModelCatalogService;
import com.shangan.common.integration.RuntimeIntegrationSettings;
import com.shangan.common.integration.RuntimeIntegrationSettingsService;
import com.shangan.media.emby.EmbyDtos;
import com.shangan.media.emby.EmbyGateway;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 验证 Emby 媒体库绑定独立保存，并且整份配置保存不会清空已有绑定。 */
class IntegrationSettingsAdminControllerTest {

  private final RuntimeIntegrationSettingsService settings =
      mock(RuntimeIntegrationSettingsService.class);
  private final EmbyGateway emby = mock(EmbyGateway.class);
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new IntegrationSettingsAdminController(
                    settings,
                    mock(OpenRouterModelCatalogService.class),
                    mock(IntegrationConnectionTestService.class),
                    emby,
                    new EmbyLibraryBindingValidator()))
            .build();
  }

  @Test
  void savesMultipleVisibleLibrariesWithTheirSelectedTypes() throws Exception {
    when(emby.listMediaLibraries())
        .thenReturn(
            List.of(
                new EmbyDtos.MediaLibrary("library-1", "剧集库", "tvshows"),
                new EmbyDtos.MediaLibrary("library-2", "电影库", "movies")));

    mockMvc
        .perform(
            post("/admin/settings/integrations/emby-libraries")
                .param("librarySelection", "library-1|SERIES", "library-2|MOVIE"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.message").value("媒体库绑定已保存，共 2 个"));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<RuntimeIntegrationSettings.EmbyLibrary>> captor =
        ArgumentCaptor.forClass(List.class);
    verify(settings).saveEmbyLibraries(captor.capture());
    assertThat(captor.getValue())
        .extracting(
            RuntimeIntegrationSettings.EmbyLibrary::id,
            RuntimeIntegrationSettings.EmbyLibrary::contentType)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "library-1", RuntimeIntegrationSettings.EmbyLibraryType.SERIES),
            org.assertj.core.groups.Tuple.tuple(
                "library-2", RuntimeIntegrationSettings.EmbyLibraryType.MOVIE));
  }

  @Test
  void fullSettingsSavePreservesExistingLibraryBindings() throws Exception {
    RuntimeIntegrationSettings.EmbyLibrary binding =
        new RuntimeIntegrationSettings.EmbyLibrary(
            "library-1", "混合库", RuntimeIntegrationSettings.EmbyLibraryType.MIXED);
    when(settings.current())
        .thenReturn(
            new RuntimeIntegrationSettings(
                new RuntimeIntegrationSettings.Emby("", "", ""),
                List.of(binding),
                RuntimeIntegrationSettings.Asr.defaults(),
                RuntimeIntegrationSettings.Llm.defaults(),
                new RuntimeIntegrationSettings.OpenRouter(""),
                RuntimeIntegrationSettings.AutoFill.defaults(),
                0));
    when(settings.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

    mockMvc
        .perform(post("/admin/settings/integrations"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));

    ArgumentCaptor<RuntimeIntegrationSettings> captor =
        ArgumentCaptor.forClass(RuntimeIntegrationSettings.class);
    verify(settings).save(captor.capture());
    assertThat(captor.getValue().embyLibraries()).containsExactly(binding);
  }
}
