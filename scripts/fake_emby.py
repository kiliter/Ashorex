#!/usr/bin/env python3
"""本机开发用的假 Emby 服务。

只用于在没有真实 Emby 的开发机上验证「课程库 / Emby 同步」两个管理后台页面。
仅使用 Python 标准库，不引入任何依赖，也不参与服务端编译与测试 classpath。

实现的是 EmbyClient / EmbyHealthService 真正调用到的最小接口子集：

    GET  /System/Info                         健康探测（EmbyHealthService）
    GET  /Users/{userId}/Views                可见媒体库列表（listMediaLibraries）
    GET  /Users/{userId}/Items/{itemId}       单个条目（getSource / getSourceMetadata / readParent）
    GET  /Users/{userId}/Items?ParentId=...   分页子项（searchSources / listChildren）

另有三个仅本地可用的控制接口，用于构造「远端条目消失」场景：

    POST /__fake/drop-episodes                让预先指定的两个课时从远端消失
    POST /__fake/drop-course                  让第三门课程整体 404（触发课程失联）
    POST /__fake/restore                      恢复全部条目
    GET  /__fake/state                        查看当前状态

数据里的 Path 字段刻意使用带哨兵串 FAKEPATH 的假路径，方便验证 AGENTS.md 的硬约束：
Emby 原始路径只能在服务端内存中用于生成 SHA-256 来源指纹，绝不能进入数据库、页面、
API 响应或日志。验证方式是在全部后台响应与服务端日志里 grep 哨兵串，命中数必须为 0。

用法：
    python3 scripts/fake_emby.py                 # 监听 127.0.0.1:18099
    FAKE_EMBY_PORT=19099 python3 scripts/fake_emby.py
"""

from __future__ import annotations

import json
import os
import re
import sys
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

HOST = os.environ.get("FAKE_EMBY_HOST", "127.0.0.1")
PORT = int(os.environ.get("FAKE_EMBY_PORT", "18099"))
API_KEY = os.environ.get("FAKE_EMBY_API_KEY", "fake-emby-api-key-local-dev-only")
USER_ID = os.environ.get("FAKE_EMBY_USER_ID", "fakeuser00000000000000000000abcd")

TICKS_PER_MS = 10_000

# 哨兵串：假路径里唯一且不会自然出现的标记，专门用于泄露检测。
PATH_SENTINEL = "FAKEPATH"
PATH_ROOT = f"/mnt/fake-media/{PATH_SENTINEL}"

LIBRARY_ID = "fakelib0000000000000000000000001"
LIBRARY_NAME = "上岸测试课程库"

# ---------- 假数据 ----------
# 标题刻意使用中文长标题与书名号，用来验证后台表格的中文列宽与换行。


def _episode(
    course_slug: str, index: int, title: str, minutes: int, seconds: int = 0
) -> dict[str, Any]:
    """构造一个课时；Path 使用带哨兵串的假路径，仅供来源指纹计算。"""
    return {
        "id": f"fakeep{course_slug}{index:02d}".ljust(32, "0")[:32],
        "name": title,
        "sort_name": f"{course_slug}-{index:04d}",
        # 真实 Emby 会为 Episode 返回 IndexNumber（集号）；它是课时序号的唯一权威来源。
        "index_number": index,
        "duration_ms": (minutes * 60 + seconds) * 1000,
        "path": f"{PATH_ROOT}/{course_slug}/{index:02d} {title}.mkv",
    }


COURSES: list[dict[str, Any]] = [
    {
        "id": "fakeseries000000000000000000cpa1",
        "name": "注册会计师《会计》全程精讲班（2026 新大纲）",
        "sort_name": "cpa-kuaiji",
        "overview": "按 2026 年新大纲重录的会计科目全程精讲，覆盖存货、固定资产、收入与合并报表。",
        "year": 2026,
        "genres": ["财经", "考试辅导"],
        "tags": ["CPA", "精讲班", "2026 新大纲"],
        "people": [
            {"Name": "张伟", "Type": "Director"},
            {"Name": "李静怡", "Type": "Actor"},
        ],
        "episodes": [
            _episode("cpa", 1, "第01讲 会计概述与会计基本假设", 47, 12),
            _episode("cpa", 2, "第02讲 存货的确认与初始计量", 52, 40),
            _episode("cpa", 3, "第03讲 固定资产折旧与减值测试", 58, 5),
            _episode("cpa", 4, "第04讲 长期股权投资权益法核算", 61, 30),
            _episode("cpa", 5, "第05讲 收入确认五步法模型详解", 55, 18),
            _episode("cpa", 6, "第06讲 金融资产分类与后续计量", 49, 52),
            _episode("cpa", 7, "第07讲 所得税递延资产与负债", 63, 8),
            _episode("cpa", 8, "第08讲 合并财务报表编制入门", 72, 25),
        ],
    },
    {
        "id": "fakeseries00000000000000000jjs1",
        "name": "初级经济师《经济基础知识》冲刺串讲",
        "sort_name": "jingjishi-jichu",
        "overview": "考前四周冲刺串讲，按章节梳理必考点与易错点。",
        "year": 2025,
        "genres": ["财经"],
        "tags": ["经济师", "冲刺串讲"],
        "people": [{"Name": "王建国", "Type": "Actor"}],
        "episodes": [
            _episode("jjs", 1, "第01讲 社会主义基本经济制度", 38, 20),
            _episode("jjs", 2, "第02讲 市场需求供给与均衡价格", 41, 55),
            _episode("jjs", 3, "第03讲 生产和成本理论", 36, 10),
            _episode("jjs", 4, "第04讲 货币与金融体系", 44, 33),
            _episode("jjs", 5, "第05讲 统计与统计数据", 33, 48),
            _episode("jjs", 6, "第06讲 会计基础与财务报表阅读", 47, 2),
        ],
    },
    {
        "id": "fakeseries0000000000000000cet61",
        "name": "大学英语六级听力真题精讲（含 2024 全套）",
        "sort_name": "cet6-tingli",
        "overview": "按题型精讲六级听力，配 2024 两次真题全解。",
        "year": 2024,
        "genres": ["语言学习", "考试辅导"],
        "tags": ["CET6", "听力", "真题精讲"],
        "people": [
            {"Name": "陈晓雨", "Type": "Actor"},
            {"Name": "Michael Brown", "Type": "Actor"},
        ],
        "episodes": [
            _episode("cet6", 1, "Section A 长对话精讲", 28, 44),
            _episode("cet6", 2, "Section B 听力篇章精讲", 31, 6),
            _episode("cet6", 3, "Section C 讲座讲话精讲", 35, 22),
            _episode("cet6", 4, "真题 2024 年 6 月全解", 42, 15),
            _episode("cet6", 5, "真题 2024 年 12 月全解", 45, 38),
        ],
    },
]

# 第二次同步时消失的课时：跨两门课程各一个，用于验证下架标记与重绑流程。
DROPPABLE_EPISODE_IDS = [
    COURSES[0]["episodes"][4]["id"],
    COURSES[2]["episodes"][4]["id"],
]
# 整体 404 的课程：用于验证课程失联（sourceMissing）与失联卡片。
DROPPABLE_COURSE_ID = COURSES[2]["id"]

_state_lock = threading.Lock()
_dropped_episodes: set[str] = set()
_dropped_courses: set[str] = set()


def _course_by_id(item_id: str) -> dict[str, Any] | None:
    for course in COURSES:
        if course["id"] == item_id:
            return course
    return None


def _episode_by_id(item_id: str) -> tuple[dict[str, Any], dict[str, Any]] | None:
    for course in COURSES:
        for episode in course["episodes"]:
            if episode["id"] == item_id:
                return course, episode
    return None


def _visible(item_id: str) -> bool:
    """被控制接口下架的条目对外表现为不存在。"""
    with _state_lock:
        if item_id in _dropped_courses or item_id in _dropped_episodes:
            return False
        found = _episode_by_id(item_id)
        if found is not None and found[0]["id"] in _dropped_courses:
            return False
    return True


# ---------- Emby JSON 视图 ----------


def _library_node() -> dict[str, Any]:
    return {
        "Name": LIBRARY_NAME,
        "ServerId": "fakeserver0000000000000000000001",
        "Id": LIBRARY_ID,
        "Type": "CollectionFolder",
        "CollectionType": "tvshows",
        "IsFolder": True,
        "ParentId": "fakeroot000000000000000000000001",
        "SortName": "shangan-test",
        "Path": f"{PATH_ROOT}",
    }


def _series_node(course: dict[str, Any], detailed: bool) -> dict[str, Any]:
    node: dict[str, Any] = {
        "Name": course["name"],
        "ServerId": "fakeserver0000000000000000000001",
        "Id": course["id"],
        "Type": "Series",
        "IsFolder": True,
        "ParentId": LIBRARY_ID,
        "SortName": course["sort_name"],
        "ProductionYear": course["year"],
        "MediaType": None,
        "Path": f"{PATH_ROOT}/{course['sort_name']}",
        "ChildCount": len(_live_episodes(course)),
        "RunTimeTicks": None,
    }
    if detailed:
        node.update(
            {
                "Overview": course["overview"],
                "Genres": list(course["genres"]),
                "Tags": list(course["tags"]),
                # 真实 Emby 会同时给 Tags 与 TagItems，客户端需要合并去重。
                "TagItems": [{"Name": tag} for tag in course["tags"]],
                "People": [
                    {"Name": person["Name"], "Type": person["Type"], "Id": f"person-{index}"}
                    for index, person in enumerate(course["people"])
                ],
            }
        )
    return node


def _episode_node(course: dict[str, Any], episode: dict[str, Any]) -> dict[str, Any]:
    return {
        "Name": episode["name"],
        "ServerId": "fakeserver0000000000000000000001",
        "Id": episode["id"],
        "Type": "Episode",
        "IsFolder": False,
        "MediaType": "Video",
        "ParentId": course["id"],
        "SeriesId": course["id"],
        "SeriesName": course["name"],
        "SortName": episode["sort_name"],
        "IndexNumber": episode["index_number"],
        "ParentIndexNumber": 1,
        "RunTimeTicks": episode["duration_ms"] * TICKS_PER_MS,
        "Path": episode["path"],
        "ProductionYear": course["year"],
    }


def _live_episodes(course: dict[str, Any]) -> list[dict[str, Any]]:
    with _state_lock:
        dropped = set(_dropped_episodes)
    return [episode for episode in course["episodes"] if episode["id"] not in dropped]


def _live_courses() -> list[dict[str, Any]]:
    with _state_lock:
        dropped = set(_dropped_courses)
    return [course for course in COURSES if course["id"] not in dropped]


# ---------- HTTP ----------

ITEM_PATH = re.compile(r"^/Users/(?P<user>[^/]+)/Items/(?P<item>[^/]+)$")
ITEMS_PATH = re.compile(r"^/Users/(?P<user>[^/]+)/Items$")
VIEWS_PATH = re.compile(r"^/Users/(?P<user>[^/]+)/Views$")


class FakeEmbyHandler(BaseHTTPRequestHandler):
    """单进程假 Emby；仅本机使用，因此不做限流与并发控制。"""

    protocol_version = "HTTP/1.1"
    server_version = "FakeEmby/1.0"

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
        sys.stderr.write("[假Emby] %s %s\n" % (self.address_string(), format % args))

    # --- 响应工具 ---

    def _send(self, status: int, payload: Any) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self) -> bool:
        """校验 X-Emby-Token 或 api_key，行为与真实 Emby 一致。"""
        token = self.headers.get("X-Emby-Token") or self.headers.get("X-MediaBrowser-Token")
        if not token:
            query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            token = (query.get("api_key") or query.get("ApiKey") or [""])[0]
        return token == API_KEY

    # --- 路由 ---

    def do_GET(self) -> None:  # noqa: N802
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path.removesuffix("/") or "/"
        query = urllib.parse.parse_qs(parsed.query)

        if path == "/__fake/state":
            with _state_lock:
                self._send(
                    200,
                    {
                        "droppedEpisodes": sorted(_dropped_episodes),
                        "droppedCourses": sorted(_dropped_courses),
                        "droppableEpisodes": DROPPABLE_EPISODE_IDS,
                        "droppableCourse": DROPPABLE_COURSE_ID,
                    },
                )
            return

        if path == "/System/Info" or path == "/System/Info/Public":
            if path == "/System/Info" and not self._authorized():
                self._send(401, {"error": "invalid token"})
                return
            self._send(
                200,
                {
                    "ServerName": "FakeEmby",
                    "Version": "4.8.10.0",
                    "Id": "fakeserver0000000000000000000001",
                    "OperatingSystem": "Fake",
                },
            )
            return

        if not self._authorized():
            self._send(401, {"error": "invalid token"})
            return

        views = VIEWS_PATH.match(path)
        if views:
            if views.group("user") != USER_ID:
                self._send(404, {"error": "unknown user"})
                return
            self._send(200, {"Items": [_library_node()], "TotalRecordCount": 1})
            return

        single = ITEM_PATH.match(path)
        if single:
            if single.group("user") != USER_ID:
                self._send(404, {"error": "unknown user"})
                return
            self._send_single_item(single.group("item"))
            return

        listing = ITEMS_PATH.match(path)
        if listing:
            if listing.group("user") != USER_ID:
                self._send(404, {"error": "unknown user"})
                return
            self._send_listing(query)
            return

        self._send(404, {"error": "not implemented", "path": path})

    def do_POST(self) -> None:  # noqa: N802
        path = urllib.parse.urlparse(self.path).path.removesuffix("/") or "/"
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)

        if path == "/__fake/drop-episodes":
            with _state_lock:
                _dropped_episodes.update(DROPPABLE_EPISODE_IDS)
                dropped = sorted(_dropped_episodes)
            self._send(200, {"droppedEpisodes": dropped})
            return
        if path == "/__fake/drop-course":
            with _state_lock:
                _dropped_courses.add(DROPPABLE_COURSE_ID)
                dropped = sorted(_dropped_courses)
            self._send(200, {"droppedCourses": dropped})
            return
        if path == "/__fake/restore":
            with _state_lock:
                _dropped_episodes.clear()
                _dropped_courses.clear()
            self._send(200, {"droppedEpisodes": [], "droppedCourses": []})
            return

        self._send(404, {"error": "not implemented", "path": path})

    # --- 业务 ---

    def _send_single_item(self, item_id: str) -> None:
        if not _visible(item_id):
            self._send(404, {"error": "item not found"})
            return
        if item_id == LIBRARY_ID:
            self._send(200, _library_node())
            return
        course = _course_by_id(item_id)
        if course is not None:
            # 真实 Emby 单条查询总是返回完整字段，Fields 只影响可选扩展字段。
            self._send(200, _series_node(course, detailed=True))
            return
        found = _episode_by_id(item_id)
        if found is not None:
            self._send(200, _episode_node(found[0], found[1]))
            return
        self._send(404, {"error": "item not found"})

    def _send_listing(self, query: dict[str, list[str]]) -> None:
        parent_id = (query.get("ParentId") or [""])[0]
        include_types = [
            value.strip().lower()
            for value in (query.get("IncludeItemTypes") or [""])[0].split(",")
            if value.strip()
        ]
        search_term = (query.get("SearchTerm") or [""])[0].strip().lower()
        start_index = int((query.get("StartIndex") or ["0"])[0])
        limit = int((query.get("Limit") or ["500"])[0])

        if parent_id and not _visible(parent_id):
            self._send(404, {"error": "parent not found"})
            return

        items: list[dict[str, Any]] = []
        if parent_id == LIBRARY_ID or not parent_id:
            for course in _live_courses():
                if not include_types or "series" in include_types:
                    items.append(_series_node(course, detailed=False))
                if "episode" in include_types or "video" in include_types:
                    for episode in _live_episodes(course):
                        items.append(_episode_node(course, episode))
        else:
            course = _course_by_id(parent_id)
            if course is None:
                self._send(404, {"error": "parent not found"})
                return
            for episode in _live_episodes(course):
                if not include_types or "episode" in include_types or "video" in include_types:
                    items.append(_episode_node(course, episode))

        if search_term:
            items = [item for item in items if search_term in item["Name"].lower()]
        # 真实 Emby 按 SortName 升序返回，本地资源序号依赖这个顺序。
        items.sort(key=lambda item: item.get("SortName") or item["Name"])

        total = len(items)
        page = items[start_index : start_index + limit]
        self._send(200, {"Items": page, "TotalRecordCount": total, "StartIndex": start_index})


def main() -> None:
    server = ThreadingHTTPServer((HOST, PORT), FakeEmbyHandler)
    print(f"[假Emby] 监听 http://{HOST}:{PORT}", flush=True)
    print(f"[假Emby] API Key：{API_KEY}", flush=True)
    print(f"[假Emby] User ID：{USER_ID}", flush=True)
    print(
        f"[假Emby] {len(COURSES)} 门课程 / "
        f"{sum(len(course['episodes']) for course in COURSES)} 个课时；"
        f"路径哨兵串 {PATH_SENTINEL}",
        flush=True,
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("[假Emby] 已停止", flush=True)
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
