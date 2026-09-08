/** 后台展示格式化。与 Flutter 端口径保持一致：时长按 时:分 显示，不做四舍五入。 */

/** 毫秒转 `H:MM`；不足一分钟显示 `0:00`。 */
export function formatDuration(milliseconds: number | null | undefined): string {
  if (!milliseconds || milliseconds <= 0) {
    return '0:00';
  }
  const totalMinutes = Math.floor(milliseconds / 60_000);
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  return `${hours}:${String(minutes).padStart(2, '0')}`;
}

/**
 * 字节转 `B / KB / MB`，用于数据库体积与附件占用。
 *
 * 只到 MB 为止：本项目定位是少于 5 人的自托管规模，SQLite 与附件目录不会到 GB 量级，
 * 多一级单位反而让「0.0 GB」这类无信息量的读数出现在页面上。
 */
export function formatBytes(bytes: number | null | undefined): string {
  if (!bytes || bytes <= 0) {
    return '0 B';
  }
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  if (bytes < 1024 * 1024) {
    return `${(bytes / 1024).toFixed(1)} KB`;
  }
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

/** ISO-8601 转本地 `MM-DD HH:mm`；空值显示破折号。 */export function formatInstant(value: string | null | undefined): string {
  if (!value) {
    return '—';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '—';
  }
  const pad = (input: number) => String(input).padStart(2, '0');
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

const PRESENCE_LABELS: Record<string, string> = {
  ONLINE: '在线',
  IDLE: '空闲',
  OFFLINE: '离线',
};

export function presenceLabel(state: string): string {
  return PRESENCE_LABELS[state] ?? state;
}

const TODO_TYPE_LABELS: Record<string, string> = {
  COURSE: '课程',
  FOCUS: '专注',
  TASK: '待办',
};

export function todoTypeLabel(type: string): string {
  return TODO_TYPE_LABELS[type] ?? type;
}

const NAG_STATUS_LABELS: Record<string, string> = {
  PENDING: '待投递',
  DELIVERED: '已投递',
  RESPONDED: '已回应',
  EXPIRED: '已过期',
};

export function nagStatusLabel(status: string): string {
  return NAG_STATUS_LABELS[status] ?? status;
}
