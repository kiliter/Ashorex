#!/usr/bin/env python3
"""结构化比对提交的 OpenAPI 合同与 springdoc 运行时输出。

为什么不做逐字节 diff：
`docs/api/openapi.yaml` 是手写冻结合同，带中文 description、示例、手写错误码说明和
文档性 schema；springdoc 只能从代码推断出骨架。逐字节比对会把「人写的说明」判成漂移，
门禁恒红后就失去意义。本脚本只比对真正构成契约的结构：

1. 路径 + HTTP 方法集合必须完全一致（任一侧多出即失败）；
2. 请求体的必填字段集合不得缺失（两个方向都查）；
3. 运行时声明的响应状态码必须在合同里有记录；
4. 必填参数（path / query / header）集合必须一致。

刻意容忍的差异（不判失败）：
- description、summary、example、字段顺序、tags、servers、info；
- 合同额外手写的错误响应（400/401/403/404/409 等，springdoc 推断不出来）；
- 合同额外定义的文档性 schema（例如 ProblemDetail 的 errorCode 枚举说明）；
- 可选字段与可选参数的增删；
- springdoc 对 `ResponseEntity<Void>` 一律推断成 200 而合同写 204 —— 同属 2xx 视为一致；
- multipart 请求体的表单字段 —— springdoc 推断不出 `MultipartFile`，只校验媒体类型存在。
"""

from __future__ import annotations

import argparse
import json
import sys
from typing import Any, Iterable

HTTP_METHODS = ("get", "put", "post", "delete", "patch", "options", "head", "trace")
# 只比对移动端业务 API。管理后台 /admin/api/** 按 ADR-0033 刻意不进合同，
# springdoc 的 paths-to-match 也只包 /api/v1/**，两侧都过滤可避免误判。
CONTRACT_PATH_PREFIX = "/api/v1"


def load_document(path: str) -> dict[str, Any]:
    """读取 OpenAPI 文档，按扩展名选择 JSON 或 YAML 解析。"""
    with open(path, "r", encoding="utf-8") as handle:
        text = handle.read()
    if path.endswith(".json"):
        return json.loads(text)
    try:
        import yaml  # 延迟导入：只有解析 YAML 合同时才需要 PyYAML。
    except ImportError:  # pragma: no cover - 环境缺依赖时给出可执行的修复提示。
        sys.exit("[契约检查] 错误：缺少 PyYAML，请执行 python3 -m pip install pyyaml")
    return yaml.safe_load(text)


def resolve_ref(document: dict[str, Any], node: Any, seen: tuple[str, ...] = ()) -> Any:
    """沿 $ref 解引用，遇到循环引用时停止，避免无限递归。"""
    while isinstance(node, dict) and "$ref" in node:
        ref = node["$ref"]
        if not isinstance(ref, str) or not ref.startswith("#/") or ref in seen:
            return {}
        seen += (ref,)
        cursor: Any = document
        for segment in ref[2:].split("/"):
            segment = segment.replace("~1", "/").replace("~0", "~")
            if not isinstance(cursor, dict):
                return {}
            cursor = cursor.get(segment)
        node = cursor
    return node if node is not None else {}


def flatten_schema(
    document: dict[str, Any], schema: Any, seen: tuple[int, ...] = ()
) -> tuple[set[str], set[str]]:
    """把 schema 摊平成 (必填字段集合, 全部字段集合)。

    allOf 的必填与字段都要合并；oneOf / anyOf 只并入字段名，不并入必填，
    因为分支之间的必填是互斥的，强行取并集会造成误报。
    """
    schema = resolve_ref(document, schema)
    if not isinstance(schema, dict) or id(schema) in seen:
        return set(), set()
    seen += (id(schema),)
    required = {name for name in schema.get("required") or [] if isinstance(name, str)}
    properties = set((schema.get("properties") or {}).keys())
    for branch in schema.get("allOf") or []:
        branch_required, branch_properties = flatten_schema(document, branch, seen)
        required |= branch_required
        properties |= branch_properties
    for key in ("oneOf", "anyOf"):
        for branch in schema.get(key) or []:
            _, branch_properties = flatten_schema(document, branch, seen)
            properties |= branch_properties
    return required, properties


def collect_operations(document: dict[str, Any]) -> dict[tuple[str, str], dict[str, Any]]:
    """收集 (path, method) → 操作对象，并把 path 级参数下沉到每个操作。"""
    operations: dict[tuple[str, str], dict[str, Any]] = {}
    for path, item in (document.get("paths") or {}).items():
        if not isinstance(item, dict) or not path.startswith(CONTRACT_PATH_PREFIX):
            continue
        item = resolve_ref(document, item)
        shared_parameters = item.get("parameters") or []
        for method in HTTP_METHODS:
            operation = item.get(method)
            if not isinstance(operation, dict):
                continue
            merged = dict(operation)
            merged["parameters"] = list(shared_parameters) + list(operation.get("parameters") or [])
            operations[(path, method)] = merged
    return operations


def required_parameters(document: dict[str, Any], operation: dict[str, Any]) -> set[tuple[str, str]]:
    """取出必填参数的 (name, in)。path 参数按规范恒为必填，显式补齐。"""
    result: set[tuple[str, str]] = set()
    for raw in operation.get("parameters") or []:
        parameter = resolve_ref(document, raw)
        if not isinstance(parameter, dict):
            continue
        name, location = parameter.get("name"), parameter.get("in")
        if not isinstance(name, str) or not isinstance(location, str):
            continue
        if parameter.get("required") is True or location == "path":
            result.add((name, location))
    return result


def request_body_media(
    document: dict[str, Any], operation: dict[str, Any]
) -> dict[str, tuple[set[str], set[str]]]:
    """取出请求体每种媒体类型的 (必填字段, 全部字段)。"""
    body = resolve_ref(document, operation.get("requestBody") or {})
    if not isinstance(body, dict):
        return {}
    result: dict[str, tuple[set[str], set[str]]] = {}
    for media_type, media in (body.get("content") or {}).items():
        if not isinstance(media, dict):
            continue
        result[media_type.split(";")[0].strip()] = flatten_schema(document, media.get("schema") or {})
    return result


def match_media(media_type: str, candidates: Iterable[str]) -> str | None:
    """匹配媒体类型；springdoc 有时输出 */*，此时退化为唯一候选。"""
    candidates = list(candidates)
    if media_type in candidates:
        return media_type
    if media_type == "*/*" and len(candidates) == 1:
        return candidates[0]
    if media_type != "*/*" and candidates == ["*/*"]:
        return "*/*"
    return None


def status_codes(operation: dict[str, Any]) -> set[str]:
    """取出响应状态码集合，忽略 default 分支。"""
    return {str(code) for code in (operation.get("responses") or {}) if str(code) != "default"}


def is_success(code: str) -> bool:
    return code.startswith("2")


def format_operation(key: tuple[str, str]) -> str:
    path, method = key
    return f"{method.upper()} {path}"


def compare(contract: dict[str, Any], runtime: dict[str, Any]) -> list[str]:
    """执行结构比对，返回人类可读的失败原因列表。"""
    contract_ops = collect_operations(contract)
    runtime_ops = collect_operations(runtime)
    failures: list[str] = []

    contract_only = sorted(set(contract_ops) - set(runtime_ops))
    runtime_only = sorted(set(runtime_ops) - set(contract_ops))
    if contract_only:
        detail = "\n".join(f"    - {format_operation(key)}" for key in contract_only)
        failures.append(
            f"合同里写了但代码没有实现的接口（{len(contract_only)} 个）：\n{detail}\n"
            "    → 要么补上实现，要么从 docs/api/openapi.yaml 删掉。"
        )
    if runtime_only:
        detail = "\n".join(f"    - {format_operation(key)}" for key in runtime_only)
        failures.append(
            f"代码已实现但合同没有描述的接口（{len(runtime_only)} 个）：\n{detail}\n"
            "    → 请把它们补进 docs/api/openapi.yaml。"
        )

    for key in sorted(set(contract_ops) & set(runtime_ops)):
        name = format_operation(key)
        contract_op, runtime_op = contract_ops[key], runtime_ops[key]

        contract_params = required_parameters(contract, contract_op)
        runtime_params = required_parameters(runtime, runtime_op)
        missing = sorted(runtime_params - contract_params)
        if missing:
            listed = "、".join(f"{n}（in={loc}）" for n, loc in missing)
            failures.append(f"{name}：代码要求的必填参数在合同中缺失或未标必填 —— {listed}")
        extra = sorted(contract_params - runtime_params)
        if extra:
            listed = "、".join(f"{n}（in={loc}）" for n, loc in extra)
            failures.append(f"{name}：合同声明了代码并不接受的必填参数 —— {listed}")

        contract_body = request_body_media(contract, contract_op)
        runtime_body = request_body_media(runtime, runtime_op)
        if runtime_body and not contract_body:
            failures.append(f"{name}：代码接收请求体，合同却没有描述 requestBody")
        elif contract_body and not runtime_body:
            failures.append(f"{name}：合同描述了 requestBody，代码却不接收请求体")
        for media_type, (runtime_required, runtime_properties) in runtime_body.items():
            matched = match_media(media_type, contract_body.keys())
            if matched is None:
                failures.append(
                    f"{name}：合同缺少请求体媒体类型 {media_type}"
                    f"（合同现有：{'、'.join(sorted(contract_body)) or '无'}）"
                )
                continue
            if media_type.startswith("multipart/"):
                # springdoc 对 multipart 的字段推断不可靠：MultipartFile 参数不会进 schema，
                # 由自定义 HandlerMethodArgumentResolver 注入的参数（如 CurrentUser）反而会被
                # 误当成请求体。这里只校验「代码收 multipart、合同也写了 multipart」，
                # 表单字段以手写合同为准，否则必然误报。
                continue
            contract_required, contract_properties = contract_body[matched]
            undocumented = sorted(runtime_required - contract_properties)
            if undocumented:
                failures.append(
                    f"{name}：请求体（{media_type}）必填字段在合同中缺失 —— {'、'.join(undocumented)}"
                )
            unsupported = sorted(contract_required - runtime_properties)
            if unsupported:
                failures.append(
                    f"{name}：合同要求的必填字段代码并不接受 —— {'、'.join(unsupported)}"
                )

        contract_codes = status_codes(contract_op)
        runtime_codes = status_codes(runtime_op)
        for code in sorted(runtime_codes - contract_codes):
            # springdoc 对 ResponseEntity<Void> 一律推断 200，合同写 204 是更准确的事实，
            # 因此同属 2xx 时不判失败；非 2xx 缺失说明合同真的漏写了。
            if is_success(code) and any(is_success(existing) for existing in contract_codes):
                continue
            failures.append(f"{name}：代码返回的状态码 {code} 未写入合同（合同现有：{'、'.join(sorted(contract_codes)) or '无'}）")
        if any(is_success(code) for code in runtime_codes) and not any(
            is_success(code) for code in contract_codes
        ):
            failures.append(f"{name}：合同没有描述任何 2xx 成功响应")

    return failures


def main() -> int:
    parser = argparse.ArgumentParser(description="结构化比对 OpenAPI 合同与运行时输出")
    parser.add_argument("--contract", required=True, help="提交的 openapi.yaml 路径")
    parser.add_argument("--runtime", required=True, help="springdoc 运行时导出的文档路径")
    args = parser.parse_args()

    contract = load_document(args.contract)
    runtime = load_document(args.runtime)
    if not isinstance(contract, dict) or not isinstance(runtime, dict):
        print("[契约检查] 错误：OpenAPI 文档解析结果不是对象", file=sys.stderr)
        return 1

    contract_count = len(collect_operations(contract))
    runtime_count = len(collect_operations(runtime))
    failures = compare(contract, runtime)
    if failures:
        print(
            f"[契约检查] 结构比对失败：合同 {contract_count} 个操作，代码 {runtime_count} 个操作，"
            f"发现 {len(failures)} 处不一致",
            file=sys.stderr,
        )
        for index, failure in enumerate(failures, start=1):
            print(f"  {index}. {failure}", file=sys.stderr)
        print(
            "[契约检查] 只比对路径与方法集合、必填参数、必填请求体字段和响应状态码；"
            "中文说明、示例和字段顺序不会导致失败。",
            file=sys.stderr,
        )
        return 1

    print(
        f"[契约检查] 结构一致：{contract_count} 个 {CONTRACT_PATH_PREFIX}/** 操作的路径、方法、"
        "必填参数、必填请求体字段与响应状态码均匹配"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
