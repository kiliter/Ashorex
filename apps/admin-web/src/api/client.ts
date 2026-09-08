/**
 * 后台 API 客户端。
 *
 * 认证沿用 Spring Security 的 HttpOnly Session Cookie，因此所有请求都带 credentials；
 * 写操作必须回传 CSRF 令牌：Spring 通过 XSRF-TOKEN Cookie 下发，请求头用 X-XSRF-TOKEN。
 * 令牌不保存在 JS 变量里，每次从 Cookie 现读，避免会话轮换后使用过期值。
 */

/** 后端 RFC 9457 Problem Details 中我们依赖的字段。 */
export interface ProblemDetail {
  title?: string;
  detail?: string;
  status?: number;
  errorCode?: string;
  /** 字段级校验错误：字段名 → 错误文案。 */
  fieldErrors?: Record<string, string>;
}

/** 调用失败时抛出，页面据此展示文案或字段错误。 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly problem: ProblemDetail,
  ) {
    super(problem.detail ?? problem.title ?? `请求失败（HTTP ${status}）`);
    this.name = 'ApiError';
  }

  /** 会话过期或未登录，调用方应跳转登录页。 */
  get unauthenticated(): boolean {
    return this.status === 401 || this.status === 403;
  }

  get fieldErrors(): Record<string, string> {
    return this.problem.fieldErrors ?? {};
  }
}

const BASE = '/admin/api';

/** 后端在 CSRF 校验失败时返回的稳定错误码，与权限不足区分开。 */
const CSRF_ERROR_CODE = 'ADMIN_CSRF_INVALID';

function readCookie(name: string): string | null {
  const prefix = `${name}=`;
  for (const part of document.cookie.split('; ')) {
    if (part.startsWith(prefix)) {
      return decodeURIComponent(part.slice(prefix.length));
    }
  }
  return null;
}

/**
 * 确保拿到可用的 CSRF 令牌。
 *
 * Spring Security 的令牌是延迟加载的，且会话固化防护会在登录成功与登出时清空
 * XSRF-TOKEN Cookie。因此「Cookie 里没有令牌」是正常状态而非异常，
 * 需要先读一次 `GET /admin/api/session`（该端点会主动生成令牌）把它补回来。
 *
 * 不做这一步的话，登出后紧接着再次登录会因为缺令牌被判 CSRF 失败。
 */
export async function ensureCsrfToken(): Promise<string | null> {
  const existing = readCookie('XSRF-TOKEN');
  if (existing) {
    return existing;
  }
  await fetch(`${BASE}/session`, {
    method: 'GET',
    headers: { Accept: 'application/json' },
    credentials: 'same-origin',
  });
  return readCookie('XSRF-TOKEN');
}

async function toProblem(response: Response): Promise<ProblemDetail> {
  try {
    const body = await response.json();
    if (body && typeof body === 'object') {
      return body as ProblemDetail;
    }
  } catch {
    // 非 JSON 响应（例如反向代理返回的 HTML 错误页）时退回状态码文案。
  }
  return { status: response.status, title: `请求失败（HTTP ${response.status}）` };
}

async function send(
  method: string,
  path: string,
  body: unknown,
  csrf: string | null,
): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (csrf) {
    headers['X-XSRF-TOKEN'] = csrf;
  }
  return fetch(`${BASE}${path}`, {
    method,
    headers,
    credentials: 'same-origin',
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

async function request<T>(
  method: string,
  path: string,
  body?: unknown,
): Promise<T> {
  const mutating = method !== 'GET' && method !== 'HEAD';
  let response = await send(
    method,
    path,
    body,
    mutating ? await ensureCsrfToken() : null,
  );

  // 令牌可能在两次请求之间被服务端轮换（会话固化、登出、会话过期都会触发）。
  // 这种失败是可自愈的：重新取一次令牌后重放同一请求，只重试一次避免死循环。
  if (mutating && response.status === 403) {
    const problem = await toProblem(response.clone());
    if (problem.errorCode === CSRF_ERROR_CODE) {
      const refreshed = await forceRefreshCsrfToken();
      response = await send(method, path, body, refreshed);
    }
  }

  if (!response.ok) {
    throw new ApiError(response.status, await toProblem(response));
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

/** 丢弃当前令牌并重新获取，用于令牌被服务端轮换后的重试。 */
async function forceRefreshCsrfToken(): Promise<string | null> {
  await fetch(`${BASE}/session`, {
    method: 'GET',
    headers: { Accept: 'application/json' },
    credentials: 'same-origin',
  });
  return readCookie('XSRF-TOKEN');
}

export const api = {
  get: <T>(path: string) => request<T>('GET', path),
  post: <T>(path: string, body?: unknown) => request<T>('POST', path, body),
  patch: <T>(path: string, body?: unknown) => request<T>('PATCH', path, body),
  delete: <T>(path: string, body?: unknown) => request<T>('DELETE', path, body),
};
