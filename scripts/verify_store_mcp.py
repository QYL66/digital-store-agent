"""Verify live MCP tool results and PostgreSQL slot contention through the isolated service."""

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import json
import os
import re
from threading import Barrier
from urllib.error import HTTPError
from urllib.request import Request, urlopen

META = "io.github.qyl66.labdesk/userId"
TOKEN_HEADER = "X-LabDesk-Mcp-Token"


class Client:
    def __init__(self, url, token):
        self.url = url
        self.headers = {"Accept": "application/json, text/event-stream", "Content-Type": "application/json"}
        if token:
            self.headers[TOKEN_HEADER] = token
        self.sequence = 0
        result, headers = self.call("initialize", {"protocolVersion": "2025-03-26",
                                                    "capabilities": {},
                                                    "clientInfo": {"name": "store-agent-check", "version": "1"}})
        self.headers["Mcp-Session-Id"] = headers["Mcp-Session-Id"]
        self.headers["MCP-Protocol-Version"] = result["protocolVersion"]
        self.call("notifications/initialized", {}, notification=True)

    def call(self, method, args, notification=False):
        self.sequence += 1
        payload = {"jsonrpc": "2.0", "method": method, "params": args}
        if not notification:
            payload["id"] = self.sequence
        with urlopen(Request(self.url, json.dumps(payload).encode(), self.headers), timeout=20) as response:
            content = response.read().decode()
            if notification:
                return None, response.headers
            if response.headers.get_content_type() == "text/event-stream":
                content = next(line[5:] for line in content.splitlines() if line.startswith("data:"))
            decoded = json.loads(content)
            if "error" in decoded:
                raise RuntimeError(decoded["error"])
            return decoded["result"], response.headers

    def tools(self):
        return self.call("tools/list", {})[0]["tools"]

    def tool(self, name, args=None, user=None):
        params = {"name": name, "arguments": args or {}}
        if user:
            params["_meta"] = {META: user}
        result, _ = self.call("tools/call", params)
        return bool(result.get("isError")), "\n".join(item["text"] for item in result["content"])


def check(condition, message):
    if not condition:
        raise AssertionError(message)
    print("PASS " + message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=29099)
    parser.add_argument("--first", required=True)
    parser.add_argument("--second", required=True)
    args = parser.parse_args()
    url = f"http://127.0.0.1:{args.port}/mcp"
    token = os.environ["STORE_AGENT_MCP_TOKEN"]
    try:
        Client(url, None)
        raise AssertionError("unauthenticated MCP accepted")
    except HTTPError as e:
        check(e.code == 401, "MCP requires a service token")
    first, second = Client(url, token), Client(url, token)
    tools = {tool["name"]: tool.get("annotations", {}) for tool in first.tools()}
    check("lab_book_equipment" not in tools and len(tools) == 9, "only the store tools are exposed")
    check(tools["store_create_demo_booking"]["readOnlyHint"] is False, "booking is annotated as a write")
    error, comparison = first.tool("store_compare", {"first": "K90", "second": "K90 Pro Max"})
    check(not error and "7100" in comparison and "K90 Pro Max" in comparison and "https://" in comparison,
          "K90 comparison returns model-specific values and sources")
    error, compatibility = first.tool("store_compatibility", {"model": "K90", "accessory": "PB2165"})
    check(not error and "系列级" in compatibility and "未确认" in compatibility,
          "series evidence does not become model-specific wattage")
    error, policy = first.tool("store_policy", {"topic": "优惠券"})
    check(not error and "叠加" in policy and "mi.com" in policy, "coupon query returns the applicable rule")
    error, _ = first.tool("store_product", {"kind": "phone", "name": "REDMI K"})
    check(error, "ambiguous series is sent back for clarification")
    error, source = first.tool("store_service_centers", {"city": "哈尔滨"})
    check(not error and "mi.com/service/sitelist" in source, "real service directory links to its official source")

    date = (datetime.now(timezone(timedelta(hours=8))) + timedelta(days=2)).date().isoformat()
    error, slots = first.tool("store_demo_slots", {"model": "K90", "city": "哈尔滨", "date": date})
    match = re.search(r"HRB-A[^\n]*：(09:00|1[0-7]:00)", slots)
    check(not error and match is not None and "演示" in slots, "real database returns an available demo slot")
    params = {"model": "K90", "centerCode": "HRB-A", "symptom": "无法充电", "date": date, "time": match[1]}
    create = "store_create_demo_booking"
    check(first.tool(create, params)[0], "booking rejects missing user metadata")
    check(first.tool(create, params | {"centerCode": "HRB-B"}, args.first)[0], "booking rejects unsupported brand")
    check(first.tool(create, params | {"time": "16:30"}, args.first)[0], "booking rejects invalid time")
    check(first.tool(create, params | {"date": "2000-01-01"}, args.first)[0], "booking rejects past time")

    barrier = Barrier(2)

    def book(client, user):
        barrier.wait(8)
        return client.tool(create, params, user)

    with ThreadPoolExecutor(max_workers=2) as pool:
        a = pool.submit(book, first, args.first)
        b = pool.submit(book, second, args.second)
        results = [a.result(), b.result()]
    check(sum(not error for error, _ in results) == 1, "concurrent users compete for exactly one slot")
    index = next(i for i, (error, _) in enumerate(results) if not error)
    winner, loser = ((args.first, args.second) if index == 0 else (args.second, args.first))
    appointment = re.search(r"DS-\d+", results[index][1])[0]
    check(appointment in first.tool(create, params, winner)[1], "idempotent retry returns same booking")
    check(first.tool(create, params | {"symptom": "充电慢"}, winner)[0], "same owner with different input conflicts")
    check(appointment in first.tool("store_my_demo_bookings", user=winner)[1], "owner can query same booking")
    check(appointment not in second.tool("store_my_demo_bookings", user=loser)[1], "other user cannot see booking")


if __name__ == "__main__":
    main()
