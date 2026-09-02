/**
 * 后台 Emby 来源选择器：只请求服务端安全 DTO，不直接接触 Emby 主机、密钥或物理路径。
 * 同一实现同时支持课程批量添加和已有课程的单来源重新绑定。
 */
(() => {
  const MAX_SOURCES = 50;
  const SEARCH_DELAY_MS = 220;

  document.querySelectorAll("[data-source-picker]").forEach((root) => {
    const queryInput = root.querySelector("[data-source-query]");
    const options = root.querySelector("[data-source-options]");
    const status = root.querySelector("[data-source-status]");
    const tray = root.querySelector("[data-source-tray]");
    const hidden = root.querySelector("[data-source-hidden]");
    const empty = root.querySelector("[data-source-empty]");
    const count = root.querySelector("[data-source-count]");
    const singleSubmit = root.querySelector("[data-single-submit]");
    const batchSubmit = root.querySelector("[data-batch-submit]");
    const manualInput = root.querySelector("[data-manual-source]");
    const mode = root.dataset.mode || "single";
    const selected = new Map();
    let candidates = [];
    let activeIndex = -1;
    let timer;
    let request;

    const typeLabel = (source) => {
      if (source.itemType === "CollectionFolder") {
        return source.collectionType ? `媒体库 · ${source.collectionType}` : "媒体库 · 混合视频";
      }
      return {
        Series: "剧集",
        Movie: "电影",
        Folder: "文件夹",
      }[source.itemType] || source.itemType || "文件夹";
    };

    const setExpanded = (expanded) => {
      root.querySelector("[role='combobox']")?.setAttribute("aria-expanded", String(expanded));
      options.hidden = !expanded;
    };

    const updateActions = () => {
      const size = selected.size;
      const manualReady = Boolean(manualInput?.value.trim());
      const manualIsSelected = manualReady && selected.has(manualInput.value.trim());
      const effectiveSize = size + (manualReady && !manualIsSelected ? 1 : 0);
      const overLimit = effectiveSize > MAX_SOURCES;
      if (singleSubmit) {
        singleSubmit.disabled = overLimit || (mode === "multiple" ? effectiveSize !== 1 : size !== 1 && !manualReady);
      }
      if (batchSubmit) {
        batchSubmit.disabled = overLimit || effectiveSize < 2;
      }
      if (count) {
        count.textContent = `${effectiveSize} / ${MAX_SOURCES}`;
      }
      if (overLimit) {
        status.textContent = `每次最多选择 ${MAX_SOURCES} 个来源，请移除一个来源或清空手工 Item ID。`;
      }
    };

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
          renderOptions();
        });
        card.append(copy, remove);
        tray.append(card);

        const field = document.createElement("input");
        field.type = "hidden";
        field.name = mode === "multiple" ? "sourceIds" : "selectedParentItemId";
        field.value = source.id;
        hidden.append(field);
      });
      updateActions();
    };

    const choose = (source) => {
      if (mode === "single") {
        selected.clear();
      }
      if (!selected.has(source.id) && selected.size >= MAX_SOURCES) {
        status.textContent = `每次最多选择 ${MAX_SOURCES} 个来源，请先移除其他来源。`;
        return;
      }
      selected.set(source.id, source);
      queryInput.value = "";
      renderTray();
      setExpanded(false);
      status.textContent = mode === "single" ? "已选择来源，可以预览映射。" : "可以继续搜索并加入更多来源。";
    };

    const renderOptions = () => {
      options.replaceChildren();
      if (candidates.length === 0) {
        const noResult = document.createElement("div");
        noResult.className = "source-option source-option--empty";
        noResult.textContent = "没有找到可用来源，请换一个名称或 Item ID。";
        options.append(noResult);
        activeIndex = -1;
        return;
      }
      activeIndex = Math.min(activeIndex, candidates.length - 1);
      candidates.forEach((source, index) => {
        const option = document.createElement("button");
        option.type = "button";
        option.className = "source-option";
        option.role = "option";
        option.dataset.optionIndex = String(index);
        option.setAttribute("aria-selected", String(selected.has(source.id)));
        if (index === activeIndex) {
          option.classList.add("is-active");
        }
        const title = document.createElement("strong");
        title.textContent = source.name;
        const meta = document.createElement("span");
        meta.textContent = `${typeLabel(source)} · ${source.id}`;
        const state = document.createElement("em");
        state.textContent = selected.has(source.id) ? "已选择" : "选择";
        option.append(title, meta, state);
        option.addEventListener("mousedown", (event) => event.preventDefault());
        option.addEventListener("click", () => choose(source));
        options.append(option);
      });
    };

    const search = async () => {
      request?.abort();
      const query = queryInput.value.trim();
      if (!query) {
        candidates = [];
        activeIndex = -1;
        setExpanded(false);
        status.textContent = "输入关键字查找剧集或电影；也可以展开高级兼容入口填写 Item ID。";
        return;
      }
      request = new AbortController();
      status.textContent = "正在读取可用媒体来源…";
      setExpanded(true);
      try {
        const url = new URL(root.dataset.searchUrl, window.location.origin);
        url.searchParams.set("query", query);
        const response = await fetch(url, {
          headers: { Accept: "application/json" },
          signal: request.signal,
          credentials: "same-origin",
        });
        if (!response.ok) {
          throw new Error("source search failed");
        }
        candidates = await response.json();
        activeIndex = candidates.length > 0 ? 0 : -1;
        renderOptions();
        status.textContent = candidates.length > 0
          ? `找到 ${candidates.length} 个可用来源，使用方向键和回车也可以选择。`
          : "没有匹配结果，请换个名称，或检查 Emby 系统配置中的媒体库绑定。";
      } catch (error) {
        if (error.name === "AbortError") return;
        candidates = [];
        renderOptions();
        status.textContent = "来源读取失败，请检查 Emby 配置或稍后重试。";
      }
    };

    const scheduleSearch = () => {
      window.clearTimeout(timer);
      timer = window.setTimeout(search, SEARCH_DELAY_MS);
    };

    queryInput.addEventListener("focus", search);
    queryInput.addEventListener("input", scheduleSearch);
    queryInput.addEventListener("keydown", (event) => {
      if (event.key === "Escape") {
        setExpanded(false);
        return;
      }
      if (event.key !== "ArrowDown" && event.key !== "ArrowUp" && event.key !== "Enter") return;
      if (candidates.length === 0) return;
      event.preventDefault();
      if (event.key === "ArrowDown") activeIndex = (activeIndex + 1) % candidates.length;
      if (event.key === "ArrowUp") activeIndex = (activeIndex - 1 + candidates.length) % candidates.length;
      if (event.key === "Enter" && activeIndex >= 0) {
        choose(candidates[activeIndex]);
        return;
      }
      renderOptions();
      options.querySelector(".is-active")?.scrollIntoView({ block: "nearest" });
    });
    queryInput.addEventListener("blur", () => {
      // 失焦时同时取消延迟任务和在途请求，防止候选层在隐藏后被异步搜索再次展开。
      window.clearTimeout(timer);
      request?.abort();
      setExpanded(false);
    });
    manualInput?.addEventListener("input", updateActions);
    renderTray();
  });
})();
