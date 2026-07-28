#!/usr/bin/env bash
#
# Container smoke suite for the Tapstate server image.
#
# The container counterpart of the jar-level dist smoke: it verifies the *built image* as a black box,
# the things that only exist once the jar is wrapped in an image and run by a container runtime, which
# a JVM unit test and the dist smoke cannot see:
#   1. the container boots against a store and its built-in HEALTHCHECK reaches "healthy" -- the exact
#      signal a compose `depends_on: service_healthy` gates a dependent on -- while running as the
#      unprivileged uid;
#   2. `docker stop` delivers SIGTERM to java as PID 1 and the container exits 0 (an orderly stop),
#      not the 137 (128+9, SIGKILL at the grace deadline) or 143 (128+15, the JVM's default signal
#      disposition) a broken shutdown path would report;
#   3. an unsupported --role is rejected with the coded diagnostic and a non-zero exit, before the
#      application context starts.
#
# A store is required for the boot check, not optional: /healthz lives in the control REST layer, which
# only comes up once the store does, so a store-less boot serves HTTP but answers /healthz with 404 and
# never turns healthy. The suite therefore brings up a throwaway single-member replica set -- one write
# path opens a transaction, and MongoDB offers transactions only on a replica set -- and tears it down
# after. This stays far lighter than the full end-to-end (no source database, no pipeline, no CLI): it
# asserts the image, not a data flow.
#
# The same image is built once for the host's native architecture and smoked here; the multi-arch
# (amd64 + arm64) publish is a separate release step. Running this suite on a native runner of each
# architecture is how both are covered without emulation.
#
# Usage:
#   deploy/docker/image-smoke.sh [--build] [IMAGE]
#     --build   build the boot jar and the image first
#               (mvn -pl app -am clean package -DskipTests; docker build -f deploy/docker/Dockerfile .)
#     IMAGE     image ref to smoke (default: tapstate:image-smoke)
#
# Exit 0 iff every check passes.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DOCKERFILE="deploy/docker/Dockerfile"
IMAGE="tapstate:image-smoke"
DO_BUILD=0
for arg in "$@"; do
  case "$arg" in
    --build) DO_BUILD=1 ;;
    -*) echo "unknown option: $arg" >&2; exit 2 ;;
    *)  IMAGE="$arg" ;;
  esac
done

red()   { printf '\033[31m%s\033[0m\n' "$1"; }
green() { printf '\033[32m%s\033[0m\n' "$1"; }
bold()  { printf '\033[1m%s\033[0m\n' "$1"; }

PASS=0
FAIL=0
# check / check_not gate on a command's exit status; the `if` guard keeps `set -e` from aborting the
# suite on an asserted-false condition. check passes when the command succeeds, check_not when it fails.
check() {
  local desc="$1"; shift
  if "$@"; then green "  PASS: $desc"; PASS=$((PASS + 1)); else red "  FAIL: $desc"; FAIL=$((FAIL + 1)); fi
}
check_not() {
  local desc="$1"; shift
  if "$@"; then red "  FAIL: $desc"; FAIL=$((FAIL + 1)); else green "  PASS: $desc"; PASS=$((PASS + 1)); fi
}

NET="tapstate-smoke-net-$$"
MONGO="tapstate-smoke-mongo-$$"
SERVER="tapstate-image-smoke-$$"
WORK="$(mktemp -d)"
# Containers before the network they attach to (a network with attached containers will not remove).
trap 'docker rm -f "$SERVER" "$MONGO" >/dev/null 2>&1 || true; docker network rm "$NET" >/dev/null 2>&1 || true; rm -rf "$WORK"' EXIT

health_of() { docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$1" 2>/dev/null || echo gone; }
is_running() { [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null || echo false)" = "true" ]; }

if [[ $DO_BUILD -eq 1 ]]; then
  # `clean` so a prior ${revision} (e.g. a stale -SNAPSHOT) jar cannot linger beside the freshly built
  # one and make the Dockerfile's single-file COPY glob match two files and fail.
  bold "building boot jar (mvn -pl app -am clean package -DskipTests)…"
  ( cd "$REPO_ROOT" && mvn -q -pl app -am -DskipTests clean package )
  bold "building image $IMAGE (docker build -f $DOCKERFILE)…"
  ( cd "$REPO_ROOT" && docker build -f "$DOCKERFILE" -t "$IMAGE" . )
fi

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  red "image not found: $IMAGE (pass --build, or build it first)"; exit 2
fi

bold "0. bring up a throwaway single-member replica set for the store"
docker network create "$NET" >/dev/null
docker run -d --name "$MONGO" --network "$NET" mongo:7.0 --replSet rs0 --bind_ip_all >/dev/null
# Wait for mongod to accept connections, then initiate the set and wait for a primary. The advertised
# member host is irrelevant: the server addresses the set with directConnection, which bypasses topology
# discovery, so it reaches the member under its service name regardless of what the set advertises.
mongo_ready=0
for _ in $(seq 1 60); do
  if docker exec "$MONGO" mongosh --quiet --eval 'db.runCommand({ ping: 1 }).ok' 2>/dev/null | grep -q 1; then
    mongo_ready=1; break
  fi
  sleep 1
done
[ "$mongo_ready" = 1 ] || { red "mongo did not accept connections"; exit 2; }
docker exec "$MONGO" mongosh --quiet --eval \
  "try { rs.status().ok } catch (e) { rs.initiate({ _id: 'rs0', members: [{ _id: 0, host: 'localhost:27017' }] }) }" \
  >/dev/null 2>&1 || true
primary=0
for _ in $(seq 1 60); do
  if docker exec "$MONGO" mongosh --quiet --eval 'db.hello().isWritablePrimary' 2>/dev/null | grep -q true; then
    primary=1; break
  fi
  sleep 1
done
[ "$primary" = 1 ] || { red "mongo replica set did not elect a primary"; exit 2; }
green "  store ready (primary elected)"

bold "1. container boots against the store, HEALTHCHECK reaches healthy, runs as the unprivileged uid"
if docker run -d --name "$SERVER" --network "$NET" \
     -e TAPSTATE_STORE_MONGO_URI="mongodb://$MONGO:27017/tapstate?directConnection=true" \
     "$IMAGE" --role=all >/dev/null; then
  # Poll the container's own health -- the signal a dependent's service_healthy waits on. Break early
  # if the container exits before turning healthy: the embedded member + engine are the keep-alive
  # plane and must hold the process up.
  status=""
  for _ in $(seq 1 120); do
    status="$(health_of "$SERVER")"
    [[ "$status" == "healthy" ]] && break
    is_running "$SERVER" || break
    sleep 1
  done
  check "container reaches healthy (built-in HEALTHCHECK → /healthz)" test "$status" = "healthy"
  check "runs as uid 10001, not root" test "$(docker exec "$SERVER" id -u 2>/dev/null || echo NA)" = "10001"
else
  red "  FAIL: docker run did not start the container"; FAIL=$((FAIL + 1))
fi

bold "2. docker stop delivers SIGTERM; the container exits 0 (orderly), not 137/143"
if is_running "$SERVER"; then
  # 30s grace mirrors the compose stop_grace_period; the orderly shutdown closes the member and the
  # store client, then halts with 0. A wedged stop is SIGKILLed at the deadline (137); an unhandled
  # one exits on the JVM's default signal disposition (143).
  docker stop -t 30 "$SERVER" >/dev/null 2>&1 || true
  ec="$(docker inspect -f '{{.State.ExitCode}}' "$SERVER" 2>/dev/null || echo NA)"
  check "exit code 0 on SIGTERM (not 137/143)" test "$ec" = "0"
  # The member's own orderly-leave line proves the shutdown hooks ran, rather than the process merely
  # happening to end at 0.
  docker logs "$SERVER" >"$WORK/run.log" 2>&1 || true
  check "member logged an orderly SHUTDOWN" grep -q "is SHUTDOWN" "$WORK/run.log"
else
  red "  FAIL: container was not running to stop (boot check failed above)"; FAIL=$((FAIL + 1))
fi

bold "3. unsupported --role is rejected with the coded diagnostic and a non-zero exit"
# No store needed: role validation is fail-fast, before the context (and any store contact) starts.
set +e
docker run --rm "$IMAGE" --role=nope >"$WORK/role-bad.txt" 2>&1; rc=$?
set -e
check     "exit 1 (coded ERROR severity)" test "$rc" -eq 1
check     "rendered the coded message"    grep -qF "Unsupported --role value 'nope'." "$WORK/role-bad.txt"
check_not "context never started (fail-fast before run)" grep -q "Started Bootstrap" "$WORK/role-bad.txt"

echo
if [[ $FAIL -eq 0 ]]; then green "image smoke: $PASS passed"; exit 0; else red "image smoke: $FAIL failed, $PASS passed"; exit 1; fi
