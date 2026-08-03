#!/usr/bin/env bash
# Black-box stdio smoke for the native sidecar or the runnable Boot jar.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ARTIFACT="${1:-}"

if [[ -z "$ARTIFACT" ]]; then
  if [[ -x "$REPO_ROOT/control/mcp-server/target/tapstate-mcp" ]]; then
    ARTIFACT="$REPO_ROOT/control/mcp-server/target/tapstate-mcp"
  else
    ARTIFACT="$(ls -t "$REPO_ROOT"/control/mcp-server/target/mcp-server-*-boot.jar 2>/dev/null | head -1 || true)"
  fi
fi

if [[ -z "$ARTIFACT" || ! -e "$ARTIFACT" ]]; then
  echo "MCP artifact not found; package control/mcp-server first" >&2
  exit 2
fi

if [[ "$ARTIFACT" == *.jar ]]; then
  COMMAND=(java -jar "$ARTIFACT")
else
  COMMAND=("$ARTIFACT")
fi

TAPSTATE_MCP_SMOKE_COMMAND="$(printf '%q ' "${COMMAND[@]}")" python3 - <<'PY'
import json
import os
import select
import shlex
import subprocess

command = shlex.split(os.environ["TAPSTATE_MCP_SMOKE_COMMAND"])
environment = dict(os.environ)
environment["TAPSTATE_TOKEN"] = "smoke-token"
environment["TAPSTATE_SERVER_URL"] = "http://127.0.0.1:1"
process = subprocess.Popen(
    command,
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    text=True,
    env=environment,
)

def send(message):
    process.stdin.write(json.dumps(message, separators=(",", ":")) + "\n")
    process.stdin.flush()

def receive(timeout=5):
    readable, _, _ = select.select([process.stdout], [], [], timeout)
    if not readable:
        process.kill()
        _, stderr = process.communicate()
        raise RuntimeError(f"MCP process did not respond within {timeout}s; stderr: {stderr!r}")
    line = process.stdout.readline()
    if not line:
        raise RuntimeError("MCP process closed stdout before responding")
    try:
        return json.loads(line)
    except json.JSONDecodeError as error:
        raise RuntimeError(f"non-protocol stdout frame: {line!r}") from error

try:
    send({
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": {
            "protocolVersion": "2025-06-18",
            "capabilities": {},
            "clientInfo": {"name": "tapstate-smoke", "version": "1"},
        },
    })
    assert receive()["result"]["protocolVersion"] == "2025-06-18"
    send({"jsonrpc": "2.0", "method": "notifications/initialized"})
    send({"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}})
    tools = receive()["result"]["tools"]
    assert len(tools) == 11
    assert "source_create" not in {tool["name"] for tool in tools}

    process.stdin.close()
    process.wait(timeout=5)
    assert process.returncode == 0
    stderr = process.stderr.read()
    assert "smoke-token" not in stderr
    print("mcp smoke: initialize, 11 read tools, clean EOF, no credential leak")
finally:
    if process.poll() is None:
        process.kill()
        process.wait(timeout=5)
PY
