"""Verify the read-only catalogue over the MCP HTTP transport."""

import argparse
import json
import os
from urllib.error import HTTPError
from urllib.request import Request, urlopen

TOKEN_HEADER = "X-Store-Agent-Mcp-Token"


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

    def tool(self, name, args=None):
        params = {"name": name, "arguments": args or {}}
        result, _ = self.call("tools/call", params)
        return bool(result.get("isError")), "\n".join(item["text"] for item in result["content"])


def check(condition, message):
    if not condition:
        raise AssertionError(message)
    print("PASS " + message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=29099)
    args = parser.parse_args()
    url = f"http://127.0.0.1:{args.port}/mcp"
    token = os.environ["STORE_AGENT_MCP_TOKEN"]
    try:
        Client(url, None)
        raise AssertionError("unauthenticated MCP accepted")
    except HTTPError as e:
        check(e.code == 401, "MCP requires a service token")
    first = Client(url, token)
    tools = {tool["name"]: tool.get("annotations", {}) for tool in first.tools()}
    check(set(tools) == {"current_date", "store_catalog", "store_product", "store_compare",
                         "store_compatibility", "store_policy", "store_support", "store_service_centers"},
          "MCP exposes only the date and catalogue tools")
    check(all(hints.get("readOnlyHint") is True for hints in tools.values()), "all exposed tools are read-only")
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

    error, listing = first.tool("store_catalog", {"kind": "phone", "keyword": "K90"})
    check(not error and "K90 Pro Max" in listing and "实时" in listing, "browse a phone series with snapshot limits")
    error, support = first.tool("store_support", {"symptom": "无法充电"})
    check(not error and "mi.com" in support, "charging support preserves the official source")


if __name__ == "__main__":
    main()
