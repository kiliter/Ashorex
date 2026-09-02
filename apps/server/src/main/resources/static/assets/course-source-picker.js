/**
 * 后台 Emby 课程选择弹窗。
 *
 * 页面只在管理员主动打开弹窗后读取候选；搜索只过滤本次已经加载的安全 DTO。
 * 正式选择与弹窗草稿分离，取消、背景关闭和 Escape 都不会改动待添加课程。
 */
(() => {
  const MAX_MULTIPLE_SOURCES = 50;

  document.querySelectorAll("[data-source-picker]").forEach((root) => {
    const openButton = root.querySelector("[data-source-open]");
    const modal = root.querySelector("[data-source-modal]");
    const closeButtons = root.querySelectorAll("[data-source-close]");
    const confirmButton = root.querySelector("[data-source-confirm]");
    const queryInput = root.querySelector("[data-source-query]");
    const options = root.querySelector("[data-source-options]");
    const status = root.querySelector("[data-source-status]");
    const tray = root.querySelector("[data-source-tray]");
    const hidden = root.querySelector("[data-source-hidden]");
    const empty = root.querySelector("[data-source-empty]");
    const pageCount = root.querySelector("[data-source-count]");
    const modalCount = root.querySelector("[data-modal-source-count]");
    const selectAllButton = root.querySelector("[data-source-select-all]");
    const singleSubmit = root.querySelector("[data-single-submit]");
    const batchSubmit = root.querySelector("[data-batch-submit]");
    const manualInput = root.querySelector("[data-manual-source]");
    const mode = root.dataset.mode || "single";
    const selectionLimit = mode === "multiple" ? MAX_MULTIPLE_SOURCES : 1;

    const selected = new Map();
    let draftSelected = new Map();
    let candidates = [];
    let candidatesLoaded = false;
    let loading = false;
    let request;

    /** 将 Emby 类型转换为管理员能直接理解的中文标签。 */
    const typeLabel = (source) => ({
      Series: "剧集",
      Movie: "电影",
      CollectionFolder: source.collectionType ? `媒体库 · ${source.collectionType}` : "媒体库",
      Folder: "文件夹",
    })[source.itemType] || source.itemType || "视频来源";

    /** 手工 Item ID 继续作为兼容入口，并与弹窗选择共同参与提交状态判断。 */
    const updateActions = () => {
      const manualId = manualInput?.value.trim() || "";
      const manualIsDuplicate = manualId && selected.has(manualId);
      const effectiveSize = selected.size + (manualId && !manualIsDuplicate ? 1 : 0);
      const overLimit = effectiveSize > MAX_MULTIPLE_SOURCES;

      if (singleSubmit) {
        // 重映射页的手工 ID 会由服务端优先采用，因此即使托盘已有联想选择也应允许预览。
        singleSubmit.disabled = overLimit
          || (mode === "multiple" ? effectiveSize !== 1 : selected.size !== 1 && !manualId);
      }
      if (batchSubmit) {
        batchSubmit.disabled = overLimit || effectiveSize < 2;
      }
      if (pageCount) {
        pageCount.textContent = `${effectiveSize} / ${MAX_MULTIPLE_SOURCES}`;
      }
      if (overLimit) {
        status.textContent = `每次最多绑定 ${MAX_MULTIPLE_SOURCES} 个来源，请先移除一个来源。`;
      }
    };

    /** 把已确认来源渲染到页面托盘，并生成服务端表单字段。 */
    const renderTray = () => {
      tray.querySelectorAll("[data-selected-source]").forEach((element) => element.remove());
      empty.hidden = selected.size > 0;
      hidden.replaceChildren();

      selected.forEach((source) => {
        const card = document.createElement("div");
        card.className = "source-chip";
        card.dataset.selectedSource = source.id;

        const copy = document.createElement("div");
        const title = document.createElement("strong");
        title.textContent = source.name;
        const meta = document.createElement("span");
        meta.textContent = `${typeLabel(source)} · ${source.id}`;
        copy.append(title, meta);

        const remove = document.createElement("button");
        remove.type = "button";
        remove.className = "source-chip__remove";
        remove.setAttribute("aria-label", `移除 ${source.name}`);
        remove.textContent = "移除";
        remove.addEventListener("click", () => {
          selected.delete(source.id);
          renderTray();
        });
        card.append(copy, remove);
        tray.append(card);

        const field = document.createElement("input");
        field.type = "hidden";
        field.name = mode === "multiple" ? "sourceIds" : "selectedParentItemId";
        // 显式写入 value 属性，确保原生表单序列化和后台自动化检查得到同一个 Item ID。
        field.setAttribute("value", source.id);
        hidden.append(field);
      });
      updateActions();
    };

    /** 当前搜索只做客户端过滤，空关键字始终展示全部已加载课程。 */
    const visibleCandidates = () => {
      const query = queryInput.value.trim().toLocaleLowerCase("zh-CN");
      if (!query) return candidates;
      return candidates.filter((source) =>
        [source.name, source.id, source.itemType, typeLabel(source)]
          .filter(Boolean)
          .some((value) => String(value).toLocaleLowerCase("zh-CN").includes(query)),
      );
    };

    /** 全选只作用于当前搜索结果，并继续遵守一次最多 50 门课程的限制。 */
    const updateSelectAllButton = (visible = visibleCandidates()) => {
      if (!selectAllButton) return;
      const selectedVisibleCount = visible.filter((source) => draftSelected.has(source.id)).length;
      const allVisibleSelected = visible.length > 0 && selectedVisibleCount === visible.length;
      selectAllButton.textContent = allVisibleSelected ? "取消全选" : "全选当前结果";
      selectAllButton.disabled = loading || visible.length === 0
        || (!allVisibleSelected && draftSelected.size >= selectionLimit);
    };

    const toggleVisibleCandidates = () => {
      const visible = visibleCandidates();
      const allVisibleSelected = visible.length > 0
        && visible.every((source) => draftSelected.has(source.id));
      if (allVisibleSelected) {
        visible.forEach((source) => draftSelected.delete(source.id));
      } else {
        for (const source of visible) {
          if (draftSelected.size >= selectionLimit) break;
          draftSelected.set(source.id, source);
        }
        if (visible.some((source) => !draftSelected.has(source.id))) {
          status.textContent = `已选择前 ${selectionLimit} 个课程，达到本次批量上限。`;
        }
      }
      updateModalCount();
      renderOptions();
    };

    const updateModalCount = () => {
      modalCount.textContent = `已选 ${draftSelected.size} / ${selectionLimit}`;
    };

    /** 点击一行即可勾选；单选重映射模式会直接替换上一次草稿选择。 */
    const toggleDraft = (source) => {
      if (draftSelected.has(source.id)) {
        draftSelected.delete(source.id);
      } else if (mode === "single") {
        draftSelected = new Map([[source.id, source]]);
      } else if (draftSelected.size < selectionLimit) {
        draftSelected.set(source.id, source);
      } else {
        status.textContent = `每次最多选择 ${selectionLimit} 个课程，请先取消一个已选项。`;
        return;
      }
      updateModalCount();
      renderOptions();
    };

    /** 候选行同时呈现课程名、类型、Item ID 和明确的勾选状态。 */
    const renderOptions = () => {
      options.replaceChildren();
      if (loading) {
        updateSelectAllButton([]);
        const loadingState = document.createElement("div");
        loadingState.className = "source-picker-loading";
        loadingState.textContent = "正在读取已绑定媒体库下的全部课程…";
        options.append(loadingState);
        return;
      }

      const visible = visibleCandidates();
      updateSelectAllButton(visible);
      if (visible.length === 0) {
        const emptyState = document.createElement("div");
        emptyState.className = "source-picker-empty";
        const title = document.createElement("strong");
        title.textContent = candidates.length === 0 ? "没有可选课程" : "没有匹配课程";
        const description = document.createElement("span");
        description.textContent = candidates.length === 0
          ? "请先到 Emby 系统配置绑定媒体库，并确认媒体库内已有剧集或电影。"
          : "清空搜索词可重新查看全部课程。";
        emptyState.append(title, description);
        options.append(emptyState);
        return;
      }

      visible.forEach((source) => {
        const isSelected = draftSelected.has(source.id);
        const row = document.createElement("button");
        row.type = "button";
        row.className = "source-picker-row";
        row.classList.toggle("is-selected", isSelected);
        row.setAttribute("role", "option");
        row.setAttribute("aria-selected", String(isSelected));
        row.setAttribute("aria-label", `${isSelected ? "取消选择" : "选择"} ${source.name}`);

        const marker = document.createElement("span");
        marker.className = "source-picker-row__check";
        marker.setAttribute("aria-hidden", "true");
        marker.textContent = isSelected ? "✓" : "";
        const copy = document.createElement("span");
        copy.className = "source-picker-row__copy";
        const title = document.createElement("strong");
        title.textContent = source.name;
        const meta = document.createElement("small");
        meta.textContent = `${typeLabel(source)} · ${source.id}`;
        copy.append(title, meta);
        const action = document.createElement("em");
        action.textContent = isSelected ? "已勾选" : "勾选";
        row.append(marker, copy, action);
        row.addEventListener("click", () => toggleDraft(source));
        options.append(row);
      });
    };

    /** 首次打开时一次读取全部候选，后续重复打开复用本页缓存。 */
    const loadCandidates = async () => {
      if (candidatesLoaded || loading) return;
      request?.abort();
      request = new AbortController();
      loading = true;
      status.textContent = "正在读取已绑定媒体库下的全部课程…";
      renderOptions();
      try {
        const url = new URL(root.dataset.searchUrl, window.location.origin);
        url.searchParams.set("query", "");
        const response = await fetch(url, {
          headers: { Accept: "application/json" },
          signal: request.signal,
          credentials: "same-origin",
        });
        if (!response.ok) throw new Error("course sources unavailable");
        const payload = await response.json();
        candidates = Array.isArray(payload) ? payload : [];
        candidatesLoaded = true;
        status.textContent = candidates.length > 0
          ? `共加载 ${candidates.length} 个课程，可直接勾选或输入关键字过滤。`
          : "当前绑定范围内没有可选课程。";
      } catch (error) {
        if (error.name === "AbortError") return;
        candidates = [];
        status.textContent = "课程读取失败，请检查 Emby 连接和媒体库绑定后重新打开弹窗。";
      } finally {
        loading = false;
        renderOptions();
      }
    };

    const openModal = () => {
      draftSelected = new Map(selected);
      queryInput.value = "";
      modal.hidden = false;
      document.body.classList.add("modal-open");
      updateModalCount();
      renderOptions();
      void loadCandidates();
      window.requestAnimationFrame(() => queryInput.focus());
    };

    /** 关闭默认丢弃草稿；只有“确定绑定”会先覆盖正式选择。 */
    const closeModal = (commit = false) => {
      if (commit) {
        selected.clear();
        draftSelected.forEach((source, id) => selected.set(id, source));
        renderTray();
      }
      modal.hidden = true;
      document.body.classList.remove("modal-open");
      openButton.focus();
    };

    openButton.addEventListener("click", openModal);
    closeButtons.forEach((button) => button.addEventListener("click", () => closeModal(false)));
    confirmButton.addEventListener("click", () => closeModal(true));
    selectAllButton?.addEventListener("click", toggleVisibleCandidates);
    queryInput.addEventListener("input", renderOptions);
    manualInput?.addEventListener("input", updateActions);
    document.addEventListener("keydown", (event) => {
      if (event.key === "Escape" && !modal.hidden) closeModal(false);
    });
    window.addEventListener("pagehide", () => request?.abort());

    renderTray();
  });
})();
