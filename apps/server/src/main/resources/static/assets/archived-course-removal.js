(() => {
  "use strict";

  const rows = Array.from(document.querySelectorAll("[data-archive-row]"));
  const checkboxes = rows.map((row) => row.querySelector("[data-archive-checkbox]"));
  const selectAll = document.querySelector("[data-archive-select-all]");
  const selectedCount = document.querySelector("[data-archive-selected-count]");
  const removeSelected = document.querySelector("[data-remove-selected]");
  const modal = document.querySelector("[data-archive-remove-modal]");
  if (!rows.length || !selectAll || !removeSelected || !modal) return;

  const selectionLimit = 50;
  let returnFocus = null;
  let previewSequence = 0;

  const impactUnits = {
    courseCount: "门",
    lessonCount: "条",
    sourceMappingCount: "条",
    courseBindingCount: "条",
    planItemCount: "条",
    debtCount: "条",
    watchSessionCount: "条",
    videoProgressCount: "条",
    questionCount: "条",
    quizAttemptCount: "条",
    studyContentCount: "条",
    contentJobCount: "条",
    quizDraftCount: "条",
    reviewEventCount: "条",
    focusSessionCount: "条",
    attachmentCount: "个",
    derivedSnapshotCount: "条",
  };

  const selectedRows = () =>
    rows.filter((row) => row.querySelector("[data-archive-checkbox]").checked);

  /** 同步整行危险色高亮、全选状态和批量按钮可用性。 */
  const updateSelection = () => {
    const selected = selectedRows();
    rows.forEach((row) => {
      row.classList.toggle(
        "is-selected",
        row.querySelector("[data-archive-checkbox]").checked,
      );
    });
    selectedCount.textContent = `已选 ${selected.length} 门`;
    removeSelected.disabled = selected.length === 0;
    selectAll.checked = selected.length === checkboxes.length && checkboxes.length <= selectionLimit;
    selectAll.indeterminate = selected.length > 0 && !selectAll.checked;
  };

  /** 从服务端读取完整关联图；只有统计成功后才允许提交危险操作。 */
  const loadPreview = async (selected, sequence) => {
    const previewUrl = new URL(modal.dataset.removePreviewUrl, window.location.origin);
    selected.forEach((row) => {
      previewUrl.searchParams.append(
        "courseIds",
        row.querySelector("[data-archive-checkbox]").value,
      );
    });
    const errorBox = modal.querySelector("[data-remove-preview-error]");
    const confirmButton = modal.querySelector("[data-remove-confirm]");
    const tokenInput = document.querySelector("[data-remove-preview-token]");
    try {
      const response = await fetch(previewUrl, {
        headers: { Accept: "application/json" },
        credentials: "same-origin",
      });
      if (!response.ok) throw new Error("preview_failed");
      const preview = await response.json();
      if (sequence !== previewSequence || modal.hidden) return;
      Object.entries(impactUnits).forEach(([name, unit]) => {
        const value = Number(preview.impact?.[name] || 0);
        modal.querySelector(`[data-impact="${name}"]`).textContent = `${value} ${unit}`;
      });
      modal.querySelector("[data-remove-dialog-count]").textContent =
        `${preview.impact.courseCount} 门课程`;
      modal.querySelector("#archive-remove-description").textContent =
        "请核对以下实时影响。确认后将永久删除完整关联数据图，此操作无法撤销。";
      tokenInput.value = preview.token;
      errorBox.hidden = true;
      confirmButton.disabled = false;
    } catch (_error) {
      if (sequence !== previewSequence || modal.hidden) return;
      tokenInput.value = "";
      errorBox.hidden = false;
      confirmButton.disabled = true;
      modal.querySelector("#archive-remove-description").textContent =
        "无法读取最新删除影响，请取消后刷新页面重试。";
    }
  };

  /** 打开确认弹窗后立即读取服务端实时影响，浏览器不自行猜测删除数量。 */
  const openConfirmation = (trigger) => {
    const selected = selectedRows();
    if (!selected.length) return;
    const names = selected.map((row) => row.dataset.courseName || "未命名课程");
    modal.querySelector("[data-remove-dialog-count]").textContent = `${selected.length} 门课程`;
    modal.querySelectorAll("[data-impact]").forEach((node) => {
      node.textContent = "读取中…";
    });
    modal.querySelector("[data-remove-course-names]").textContent = names.join("、");
    modal.querySelector("[data-remove-preview-error]").hidden = true;
    modal.querySelector("[data-remove-confirm]").disabled = true;
    document.querySelector("[data-remove-preview-token]").value = "";
    modal.querySelector("#archive-remove-description").textContent =
      "正在读取课程完整关联图，请等待统计完成后再确认。";
    returnFocus = trigger;
    modal.hidden = false;
    document.body.classList.add("modal-open");
    // 危险按钮不接收默认焦点，避免按回车误删。
    modal.querySelector("[data-remove-cancel]").focus();
    previewSequence += 1;
    void loadPreview(selected, previewSequence);
  };

  const closeConfirmation = () => {
    previewSequence += 1;
    modal.hidden = true;
    document.body.classList.remove("modal-open");
    if (returnFocus instanceof HTMLElement) returnFocus.focus();
  };

  checkboxes.forEach((checkbox) => {
    checkbox.addEventListener("change", () => {
      if (selectedRows().length > selectionLimit) {
        checkbox.checked = false;
        selectedCount.textContent = `一次最多选择 ${selectionLimit} 门`;
      }
      updateSelection();
    });
  });

  rows.forEach((row) => {
    // 点击非按钮区域时切换整行选择，让密集台账也能快速批量操作。
    row.addEventListener("click", (event) => {
      if (event.target.closest("button, a, input, label, form")) return;
      const checkbox = row.querySelector("[data-archive-checkbox]");
      if (!checkbox.checked && selectedRows().length >= selectionLimit) return;
      checkbox.checked = !checkbox.checked;
      updateSelection();
    });
    row.querySelector("[data-remove-one]").addEventListener("click", (event) => {
      checkboxes.forEach((checkbox) => {
        checkbox.checked = checkbox === row.querySelector("[data-archive-checkbox]");
      });
      updateSelection();
      openConfirmation(event.currentTarget);
    });
  });

  selectAll.addEventListener("change", () => {
    checkboxes.forEach((checkbox, index) => {
      checkbox.checked = selectAll.checked && index < selectionLimit;
    });
    updateSelection();
  });
  removeSelected.addEventListener("click", (event) => openConfirmation(event.currentTarget));
  modal.querySelectorAll("[data-remove-close]").forEach((button) => {
    button.addEventListener("click", closeConfirmation);
  });
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && !modal.hidden) closeConfirmation();
  });

  updateSelection();
})();
