#!/usr/bin/env python3
"""上岸开发环境种子数据。

幂等：重复执行不会产生重复用户或成倍的待办；已存在的数据会跳过。
全部通过真实 API 写入，因此数据一定符合服务端校验与状态机约束。

用法：
    ./run.sh seed
    BASE=http://127.0.0.1:18080 python3 scripts/seed_demo.py

可选：本机没有真实 Emby 时，先启动假 Emby 再带 FAKE_EMBY 执行，即可填充课程库
（写入 Emby 运行配置 → 绑定媒体库 → 建课并首次同步）：

    python3 scripts/fake_emby.py &
    FAKE_EMBY=http://127.0.0.1:18099 ./run.sh seed

只用于本机开发，不要指向生产环境。
"""

from __future__ import annotations

import datetime
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from http.cookiejar import CookieJar
from typing import Any

BASE = os.environ.get("BASE", f"http://127.0.0.1:{os.environ.get('SERVER_PORT', '18080')}")
ADMIN_USER = os.environ.get("ADMIN_BOOTSTRAP_USERNAME", "admin")
ADMIN_PASS = os.environ.get("ADMIN_BOOTSTRAP_PASSWORD", "123456")
DEMO_PASS = os.environ.get("SEED_DEMO_PASSWORD", "demo1234")
LEARNER_PASS = os.environ.get("SEED_LEARNER_PASSWORD", "pass1234")

TODAY = datetime.date.today().isoformat()

TASK_TITLES = [
    "整理错题本",
    "背 30 个会计科目",
    "做完一套真题",
    "复盘昨日错题",
    "默写会计公式",
    "整理讲义笔记",
    "朗读法条 20 分钟",
]


def log(message: str) -> None:
    print(f"[种子] {message}", flush=True)


def fail(message: str) -> None:
    print(f"[种子] 错误：{message}", file=sys.stderr, flush=True)
    raise SystemExit(1)


class AdminSession:
    """后台会话：Session Cookie + CSRF。

    CSRF 令牌在登录成功后会轮换，因此每次写操作都从 cookie jar 现读，不做缓存。
    """

    def __init__(self) -> None:
        self.jar = CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar)
        )

    def _csrf(self) -> str:
        for cookie in self.jar:
            if cookie.name == "XSRF-TOKEN" and cookie.value:
                return urllib.parse.unquote(cookie.value)
        return ""

    def _open(
        self,
        method: str,
        path: str,
        *,
        json_body: Any = None,
        form_body: dict[str, str] | None = None,
    ) -> tuple[int, bytes]:
        url = f"{BASE}{path}"
        data: bytes | None = None
        headers: dict[str, str] = {"Accept": "application/json"}
        if json_body is not None:
            data = json.dumps(json_body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json; charset=utf-8"
        elif form_body is not None:
            data = urllib.parse.urlencode(form_body).encode("utf-8")
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        if method not in {"GET", "HEAD"}:
            token = self._csrf()
            if token:
                headers["X-XSRF-TOKEN"] = token

        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=15) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.read()
        except urllib.error.URLError as error:
            fail(f"无法连接 {url}：{error.reason}")
            raise  # 让类型检查器满意；fail 已经抛出。

    def login(self) -> None:
        # 先取一次会话，让服务端下发 CSRF Cookie。
        self._open("GET", "/admin/api/session")
        status, body = self._open(
            "POST",
            "/admin/api/session/login",
            form_body={"username": ADMIN_USER, "password": ADMIN_PASS},
        )
        if status != 204:
            fail(
                f"管理员登录失败（HTTP {status}）：{body.decode('utf-8', 'replace')[:200]}\n"
                f"       检查 ADMIN_BOOTSTRAP_USERNAME / ADMIN_BOOTSTRAP_PASSWORD。"
            )
        # 登录会轮换 Session 与 CSRF，重新取一次拿到新令牌。
        self._open("GET", "/admin/api/session")

    def get_json(self, path: str) -> Any:
        status, body = self._open("GET", path)
        if status != 200:
            fail(f"GET {path} 返回 HTTP {status}：{body.decode('utf-8', 'replace')[:200]}")
        return json.loads(body)

    def post(self, path: str, payload: Any) -> int:
        status, _ = self._open("POST", path, json_body=payload)
        return status

    def post_json(self, path: str, payload: Any) -> tuple[int, Any]:
        """需要读取响应体的写操作；解析失败时回退为原始文本。"""
        status, body = self._open("POST", path, json_body=payload)
        if not body:
            return status, None
        try:
            return status, json.loads(body)
        except json.JSONDecodeError:
            return status, body.decode("utf-8", "replace")


def api_request(
    method: str,
    path: str,
    *,
    token: str | None = None,
    payload: Any = None,
) -> tuple[int, Any]:
    """调用学习端 API（Bearer 鉴权，无 CSRF）。"""
    url = f"{BASE}{path}"
    data = None
    headers: dict[str, str] = {"Accept": "application/json"}
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            raw = response.read()
            return response.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as error:
        raw = error.read()
        try:
            return error.code, json.loads(raw)
        except json.JSONDecodeError:
            return error.code, raw.decode("utf-8", "replace")
    except urllib.error.URLError as error:
        fail(f"无法连接 {url}：{error.reason}")
        raise


def learner_token(username: str, password: str) -> str | None:
    status, body = api_request(
        "POST", "/api/v1/auth/login", payload={"username": username, "password": password}
    )
    if status != 200 or not isinstance(body, dict) or "accessToken" not in body:
        log(f"跳过：{username} 登录失败（HTTP {status}）。")
        return None
    return body["accessToken"]


# ---------- 用户 ----------


def ensure_user(
    admin: AdminSession, username: str, display: str, password: str, supervisor: bool
) -> None:
    existing = {user["username"] for user in admin.get_json("/admin/api/users")}
    if username in existing:
        log(f"用户 {username} 已存在，跳过创建。")
        return
    status = admin.post(
        "/admin/api/users",
        {
            "username": username,
            "displayName": display,
            "password": password,
            "timezone": "Asia/Shanghai",
            "supervisor": supervisor,
        },
    )
    if status != 204:
        log(f"跳过：创建用户 {username} 失败（HTTP {status}）。")
        return
    log(f"已创建用户 {username}。")


def user_id_of(admin: AdminSession, username: str) -> str | None:
    for user in admin.get_json("/admin/api/users"):
        if user["username"] == username:
            return user["id"]
    return None


# ---------- 学习端数据 ----------


def seed_learner(
    username: str, total: int, done_count: int, delete_count: int, focus_minutes: int
) -> None:
    token = learner_token(username, LEARNER_PASS)
    if not token:
        return

    status, day = api_request("GET", "/api/v1/todos?view=DAY", token=token)
    if status == 200 and isinstance(day, dict) and day["totals"]["total"] > 0:
        log(f"{username} 今日已有 {day['totals']['total']} 条待办，跳过写入。")
        api_request("POST", "/api/v1/heartbeat", token=token, payload={"appState": "FOREGROUND"})
        return

    items = [
        {
            "todoType": "TASK",
            "localDate": TODAY,
            "title": f"{TASK_TITLES[index % len(TASK_TITLES)]}（{username}）",
            "requireEvidence": False,
        }
        for index in range(total)
    ]
    status, created = api_request("POST", "/api/v1/todos", token=token, payload={"items": items})
    if status != 200 or not isinstance(created, list):
        log(f"跳过：{username} 创建待办失败（HTTP {status}）：{str(created)[:160]}")
        return

    for index, todo in enumerate(created):
        todo_id = todo["id"]
        if index < done_count:
            api_request(
                "POST",
                f"/api/v1/todos/{todo_id}/complete",
                token=token,
                payload={"note": "种子数据：已完成", "noteTags": ["MASTERED"]},
            )
        elif index < done_count + delete_count:
            # 删除会写入 todo_deletions 台账，用于验证删除台账与原因统计。
            api_request(
                "DELETE",
                f"/api/v1/todos/{todo_id}",
                token=token,
                payload={
                    "reasonTag": "TEMP_BUSY",
                    "reasonText": "种子数据：今天临时有事，挪到明天再做。",
                },
            )

    if focus_minutes > 0:
        status, focus = api_request(
            "POST",
            "/api/v1/todos",
            token=token,
            payload={
                "items": [
                    {
                        "todoType": "FOCUS",
                        "localDate": TODAY,
                        "title": f"晚间专注 {focus_minutes} 分",
                        "plannedSeconds": focus_minutes * 60,
                        "requireEvidence": False,
                    }
                ]
            },
        )
        if status == 200 and isinstance(focus, list) and focus:
            focus_id = focus[0]["id"]
            # 起停一次即可产生真实专注时长；停在 PAUSED 便于验证运行态 UI。
            api_request("POST", f"/api/v1/todos/{focus_id}/focus/start", token=token)
            api_request("POST", f"/api/v1/todos/{focus_id}/focus/pause", token=token)

    api_request("POST", "/api/v1/heartbeat", token=token, payload={"appState": "FOREGROUND"})
    log(f"{username}：{total} 条待办（完成 {done_count} / 删除 {delete_count}）+ 专注 {focus_minutes} 分。")


def create_goal(username: str, name: str, days: int, primary: bool) -> None:
    token = learner_token(username, LEARNER_PASS)
    if not token:
        return
    status, goals = api_request("GET", "/api/v1/exam-goals", token=token)
    if status == 200 and isinstance(goals, list) and goals:
        log(f"{username} 已有 {len(goals)} 个目标，跳过。")
        return
    exam_date = (datetime.date.today() + datetime.timedelta(days=days)).isoformat()
    status, _ = api_request(
        "POST",
        "/api/v1/exam-goals",
        token=token,
        payload={"name": name, "examDate": exam_date, "note": "种子数据", "primary": primary},
    )
    if status == 200:
        log(f"{username}：已创建目标「{name}」（{days} 天后）。")
    else:
        log(f"跳过：{username} 创建目标失败（HTTP {status}）。")


# ---------- 督学与催办 ----------


def bind_supervision(admin: AdminSession, learner: str, supervisor: str) -> None:
    learner_id = user_id_of(admin, learner)
    supervisor_id = user_id_of(admin, supervisor)
    if not learner_id or not supervisor_id:
        log(f"跳过：绑定督学失败，找不到 {learner} 或 {supervisor}。")
        return
    status = admin.post(
        "/admin/api/supervisions",
        {
            "learnerUserId": learner_id,
            "supervisorUserId": supervisor_id,
            "kind": "PRIMARY",
            "canView": True,
            "canNag": True,
            "canEditGoal": False,
            "canAddTodo": False,
        },
    )
    if status == 204:
        log(f"已绑定督学：{supervisor} → {learner}。")
    elif status == 409:
        log(f"督学关系 {supervisor} → {learner} 已存在，跳过。")
    else:
        log(f"跳过：绑定督学 {supervisor} → {learner} 返回 HTTP {status}。")


def send_manual_nag(admin: AdminSession, learner: str, message: str) -> None:
    learner_id = user_id_of(admin, learner)
    if not learner_id:
        log(f"跳过：催办失败，找不到 {learner}。")
        return
    status = admin.post(
        "/admin/api/presence/nag",
        {"userId": learner_id, "message": message, "channel": "AUTO"},
    )
    if status == 204:
        log(f"已向 {learner} 投递手动催办。")
    else:
        log(f"跳过：催办 {learner} 返回 HTTP {status}。")


# ---------- 假 Emby 课程库（可选） ----------


def seed_fake_emby(admin: AdminSession) -> None:
    """把运行配置指向本机假 Emby，绑定媒体库并建课同步。

    仅在设置 FAKE_EMBY 环境变量时执行，用于在没有真实 Emby 的开发机上验证
    「课程库 / Emby 同步」两个后台页面。假服务见 scripts/fake_emby.py。

    幂等：媒体库整表重写，课程已绑定时（HTTP 409）改为触发一次同步。
    """
    base_url = os.environ.get("FAKE_EMBY", "").strip()
    if not base_url:
        return
    api_key = os.environ.get("FAKE_EMBY_API_KEY", "fake-emby-api-key-local-dev-only")
    user_id = os.environ.get("FAKE_EMBY_USER_ID", "fakeuser00000000000000000000abcd")

    log(f"假 Emby：{base_url}")
    current = admin.get_json("/admin/api/settings")
    server_chan = current.get("serverChan", {})
    features = current.get("features", {})
    status = admin.post(
        "/admin/api/settings",
        {
            "embyBaseUrl": base_url,
            "embyApiKey": api_key,
            "embyUserId": user_id,
            "embyTimeoutSeconds": 10,
            # 留空表示保持原有 SendKey，不会清掉已配置的 Server 酱。
            "serverChanSendKey": "",
            "serverChanTimeoutSeconds": server_chan.get("timeoutSeconds", 8),
            "serverChanNagEnabled": server_chan.get("nagEnabled", True),
            "serverChanDailyDigestEnabled": server_chan.get("dailyDigestEnabled", False),
            "documentResources": features.get("documentResources", False),
            "maxDocumentSizeMb": features.get("maxDocumentSizeMb", 200),
        },
    )
    if status != 204:
        log(f"跳过：写入 Emby 运行配置失败（HTTP {status}）。")
        return

    probe = admin.post_json("/admin/api/settings/test-emby", {})
    if not (isinstance(probe[1], dict) and probe[1].get("ok")):
        log(f"跳过：假 Emby 连通性测试未通过：{str(probe[1])[:160]}")
        return
    log("假 Emby 连通性测试通过。")

    libraries = admin.get_json("/admin/api/emby-sync/remote-libraries")
    if not libraries:
        log("跳过：假 Emby 未返回任何媒体库。")
        return
    type_of = {"tvshows": "SERIES", "movies": "MOVIE", "books": "BOOK"}
    status = admin.post(
        "/admin/api/emby-sync/libraries",
        {
            "libraries": [
                {
                    "id": library["id"],
                    "name": library["name"],
                    "type": type_of.get(library.get("collectionType") or "", "MIXED"),
                }
                for library in libraries
            ]
        },
    )
    if status != 204:
        log(f"跳过：保存媒体库绑定失败（HTTP {status}）。")
        return
    log(f"已绑定 {len(libraries)} 个媒体库。")

    sources = admin.get_json("/admin/api/emby-sync/search-sources?query=")
    existing = {course["title"] for course in admin.get_json("/admin/api/courses")}
    created = 0
    for index, source in enumerate(sources):
        if source["name"] in existing:
            log(f"课程「{source['name']}」已存在，触发一次同步。")
            admin.post("/admin/api/courses/sync", {"courseId": course_id_of(admin, source["name"])})
            continue
        status, body = admin.post_json(
            "/admin/api/courses", {"externalRef": source["id"], "sortOrder": index}
        )
        if status == 200 and isinstance(body, dict):
            created += 1
            log(f"已绑定课程「{body['title']}」并完成首次同步。")
        elif status == 409:
            log(f"课程来源 {source['id']} 已绑定，跳过。")
        else:
            log(f"跳过：绑定课程失败（HTTP {status}）：{str(body)[:160]}")
    rows = admin.get_json("/admin/api/courses")
    total_resources = sum(row["resourceCount"] for row in rows)
    log(f"课程库：{len(rows)} 门课程 / {total_resources} 个课时（本次新增 {created} 门）。")


def course_id_of(admin: AdminSession, title: str) -> str | None:
    for row in admin.get_json("/admin/api/courses"):
        if row["title"] == title:
            return row["id"]
    return None


# ---------- 主流程 ----------


def main() -> None:
    log(f"目标服务：{BASE}")
    try:
        with urllib.request.urlopen(f"{BASE}/actuator/health", timeout=3) as response:
            if b"UP" not in response.read():
                fail("健康检查未通过，请确认服务已就绪。")
    except (urllib.error.URLError, TimeoutError):
        fail("服务未就绪，请先执行 ./run.sh server。")

    admin = AdminSession()
    admin.login()
    log("管理员会话已建立。")

    # demo 同时具备督学与学员身份，一个账号即可验证后台与 App 两端。
    ensure_user(admin, "demo", "演示账号", DEMO_PASS, supervisor=True)
    ensure_user(admin, "lisi", "李四", LEARNER_PASS, supervisor=False)
    ensure_user(admin, "wangwu", "王五", LEARNER_PASS, supervisor=False)
    ensure_user(admin, "zhaoliu", "赵六", LEARNER_PASS, supervisor=False)

    # 覆盖三种当日形态：零完成 / 部分完成 / 全部完成。
    seed_learner("lisi", total=4, done_count=0, delete_count=1, focus_minutes=0)
    seed_learner("wangwu", total=7, done_count=5, delete_count=1, focus_minutes=45)
    seed_learner("zhaoliu", total=6, done_count=6, delete_count=0, focus_minutes=25)

    # 覆盖三种倒计时紧迫度：正常 / 临期 / 较远。
    create_goal("lisi", "注册会计师", days=82, primary=True)
    create_goal("wangwu", "初级经济师", days=14, primary=True)
    create_goal("zhaoliu", "英语六级", days=104, primary=True)

    bind_supervision(admin, "lisi", "demo")
    bind_supervision(admin, "wangwu", "demo")

    # 制造一条未回应催办，用于验证概览红色指标与催办记录页。
    send_manual_nag(admin, "lisi", "今天一项都没开始，先把第一项做完。")

    # 可选：本机没有真实 Emby 时，用假 Emby 填充课程库以验证相关页面。
    seed_fake_emby(admin)

    log("完成。")
    log(f"后台：{BASE}/admin/   管理员 {ADMIN_USER} / {ADMIN_PASS}")
    log(f"演示账号（后台与 App 通用）：demo / {DEMO_PASS}")
    log(f"学员账号：lisi、wangwu、zhaoliu，密码均为 {LEARNER_PASS}")


if __name__ == "__main__":
    main()
