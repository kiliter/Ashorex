import { defineStore } from 'pinia';
import { api, ApiError, ensureCsrfToken } from '@/api/client';

interface SessionState {
  authenticated: boolean;
  username: string | null;
  roles: string[];
  /** 首次会话探测是否已完成，避免路由守卫在未知状态下误跳转。 */
  resolved: boolean;
}

interface SessionResponse {
  authenticated: boolean;
  username: string | null;
  roles: string[];
}

export const useSessionStore = defineStore('session', {
  state: (): SessionState => ({
    authenticated: false,
    username: null,
    roles: [],
    resolved: false,
  }),
  actions: {
    /** 探测当前会话；同时触发后端下发 CSRF Cookie。 */
    async refresh(): Promise<void> {
      try {
        const data = await api.get<SessionResponse>('/session');
        this.authenticated = data.authenticated;
        this.username = data.username;
        this.roles = data.roles;
      } catch {
        this.authenticated = false;
        this.username = null;
        this.roles = [];
      } finally {
        this.resolved = true;
      }
    },

    /**
     * 登录走 Spring Security 的 formLogin，因此必须用表单编码而不是 JSON。
     * 成功后重新探测会话，拿到用户名与角色。
     *
     * 登录前必须确保 CSRF 令牌存在：登出与会话固化都会清空 XSRF-TOKEN Cookie，
     * 直接提交会被判 CSRF 失败（后端返回 ADMIN_CSRF_INVALID）。
     */
    async login(username: string, password: string): Promise<void> {
      const submit = async (csrf: string | null): Promise<Response> =>
        fetch('/admin/api/session/login', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/x-www-form-urlencoded',
            ...(csrf ? { 'X-XSRF-TOKEN': csrf } : {}),
          },
          credentials: 'same-origin',
          body: new URLSearchParams({ username, password }),
        });

      let response = await submit(await ensureCsrfToken());

      // 令牌可能在填表期间被服务端轮换（例如另一个标签页登出）。重取后重试一次。
      if (response.status === 403) {
        let errorCode: string | undefined;
        try {
          errorCode = (await response.clone().json()).errorCode;
        } catch {
          // 非 JSON 响应时按普通失败处理。
        }
        if (errorCode === 'ADMIN_CSRF_INVALID') {
          response = await submit(await ensureCsrfToken());
        }
      }

      if (!response.ok) {
        let detail = '用户名或密码错误';
        try {
          const problem = await response.json();
          detail = problem.detail ?? detail;
        } catch {
          // 保留默认文案。
        }
        throw new ApiError(response.status, { detail });
      }
      await this.refresh();
    },

    /**
     * 登出后立即补一次会话探测。
     *
     * 登出会让服务端清空 XSRF-TOKEN Cookie，而 Spring Security 的令牌是延迟加载的，
     * 不主动读一次就一直是空的；用户紧接着再次登录会因为缺令牌被拒。
     */
    async logout(): Promise<void> {
      await api.post('/session/logout');
      this.authenticated = false;
      this.username = null;
      this.roles = [];
      await ensureCsrfToken();
    },
  },
});
