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

  /** 打开确认弹窗时依据真实勾选项计算课程数和课时总数。 */
  const openConfirmation = (trigger) => {
    const selected = selectedRows();
    if (!selected.length) return;
    const lessonTotal = selected.reduce(
      (sum, row) => sum + Number.parseInt(row.dataset.lessonCount || "0", 10),
      0,
    );
    const names = selected.map((row) => row.dataset.courseName || "未命名课程");
    modal.querySelector("[data-remove-dialog-count]").textContent = `${selected.length} 门课程`;
    modal.querySelector("[data-remove-course-count]").textContent = `${selected.length} 门`;
    modal.querySelector("[data-remove-lesson-count]").textContent = `${lessonTotal} 个`;
    modal.querySelector("[data-remove-course-names]").textContent = names.join("、");
    returnFocus = trigger;
    modal.hidden = false;
    document.body.classList.add("modal-open");
    // 危险按钮不接收默认焦点，避免按回车误删。
    modal.querySelector("[data-remove-cancel]").focus();
  };

  const closeConfirmation = () => {
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
