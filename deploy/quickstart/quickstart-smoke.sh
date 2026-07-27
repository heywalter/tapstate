#!/usr/bin/env bash
#
# Test harness for deploy/quickstart/quickstart.sh. Black-box, against local file:// stubs, with no
# network and no Docker. It covers the platform gate (reused from install.sh), the prepare phase (fetch
# the compose file, the CLI, and the connector jars; generate .env and the demo work/), the demo
# workspace itself, and idempotency. The live run phase (docker compose up + the online verbs) is out of
# scope here -- the live end-to-end test covers it -- so the script runs with TAPSTATE_QUICKSTART_PREPARE_ONLY=1,
# which stops before Docker. A fake `uname` (and, for the musl case, a fake `ldd`) placed first on PATH
# drives the platform each run sees. Exit 0 iff every check passes.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"          # deploy/quickstart -> repo root
QUICKSTART_SH="$HERE/quickstart.sh"
# Read the version off the script under test rather than restating it, so the release stub always serves
# exactly what the script will ask for and the two can never drift.
VERSION="$(sed -n 's/^CLI_VERSION="\(.*\)"$/\1/p' "$QUICKSTART_SH")"
[ -n "$VERSION" ] || { printf 'cannot read CLI_VERSION from %s\n' "$QUICKSTART_SH" >&2; exit 1; }

PASS=0; FAIL=0
ok()  { printf '  PASS  %s\n' "$1"; PASS=$((PASS + 1)); }
bad() { printf '  FAIL  %s\n' "$1"; FAIL=$((FAIL + 1)); }
sha256_of() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi; }

# --- CLI release stub (what install.sh fetches): download/v<ver>/tapstate-<ver>-<platform>.tar.gz ----
CLI_STUB="$(mktemp -d)"
make_cli() {   # $1 = platform; the fake binary just echoes, so a run phase (if ever reached) won't hang
  d="$CLI_STUB/download/v$VERSION"; mkdir -p "$d"; stage="$(mktemp -d)"
  printf '#!/bin/sh\necho "tapstate %s %s"\n' "$VERSION" "$1" > "$stage/tapstate"; chmod +x "$stage/tapstate"
  echo license > "$stage/LICENSE"; echo notice > "$stage/NOTICE"
  a="tapstate-$VERSION-$1.tar.gz"; tar -czf "$d/$a" -C "$stage" tapstate LICENSE NOTICE
  ( cd "$d" && sha256_of "$a" > "$a.sha256" ); rm -rf "$stage"
}
for p in darwin-arm64 darwin-x64 linux-x64 linux-arm64; do make_cli "$p"; done

# --- quickstart asset stub: the REAL repo files (install.sh, compose, mysql-init) + fake conn jars ---
# Serving the real files means the smoke also proves the script fetches assets that actually exist at the
# paths it expects, and that the generated demo matches the real seed schema.
QS_STUB="$(mktemp -d)"
mkdir -p "$QS_STUB/install" "$QS_STUB/deploy/quickstart/mysql-init" "$QS_STUB/connectors-preview"
cp "$REPO/install/install.sh"                              "$QS_STUB/install/install.sh"
cp "$REPO/deploy/quickstart/docker-compose.yml"           "$QS_STUB/deploy/quickstart/docker-compose.yml"
cp "$REPO/deploy/quickstart/mysql-init/01-orders.sql"     "$QS_STUB/deploy/quickstart/mysql-init/01-orders.sql"
printf 'fake-mysql-connector-jar\n'   > "$QS_STUB/connectors-preview/mysql-connector.jar"
printf 'fake-mongodb-connector-jar\n' > "$QS_STUB/connectors-preview/mongodb-connector.jar"

trap 'rm -rf "$CLI_STUB" "$QS_STUB"' EXIT

# Run quickstart.sh's prepare phase in a demo dir with a faked platform.
#   run_prepare OS ARCH MUSL(glibc|musl) [REUSE_DEMO_DIR]
# Sets RC, OUT, DEMO. With REUSE_DEMO_DIR the same directory is reused (for the idempotency check).
run_prepare() {
  local fos="$1" farch="$2" fmusl="$3" reuse="${4:-}" shim
  if [ -n "$reuse" ]; then
    DEMO="$reuse"
  else
    DEMO="$(mktemp -d)/tapstate-demo"; mkdir -p "$DEMO"; cp "$QUICKSTART_SH" "$DEMO/quickstart.sh"
  fi
  shim="$(mktemp -d)"
  cat > "$shim/uname" <<EOF
#!/bin/sh
case "\$1" in -s) echo "$fos" ;; -m) echo "$farch" ;; *) echo unknown ;; esac
EOF
  chmod +x "$shim/uname"
  if [ "$fmusl" = musl ]; then
    printf '#!/bin/sh\necho "musl libc (x86_64)"\n' > "$shim/ldd"; chmod +x "$shim/ldd"
  fi
  # A recording xattr, so a test can prove the quarantine strip fires on macOS and nowhere else.
  cat > "$shim/xattr" <<EOF
#!/bin/sh
echo "\$*" >> "$DEMO/.xattr-calls"
EOF
  chmod +x "$shim/xattr"
  OUT="$(cd "$DEMO" && PATH="$shim:$PATH" \
    TAPSTATE_VERSION="${PIN_VERSION-$VERSION}" \
    TAPSTATE_BASE_URL="file://$CLI_STUB" \
    TAPSTATE_QUICKSTART_BASE_URL="file://$QS_STUB" \
    TAPSTATE_CONNECTORS_URL="file://$QS_STUB/connectors-preview" \
    TAPSTATE_QUICKSTART_PREPARE_ONLY=1 \
    sh "$DEMO/quickstart.sh" 2>&1)"
  RC=$?
  rm -rf "$shim"
}

printf '\033[1mquickstart smoke — %s\033[0m\n' "$QUICKSTART_SH"

# --- platform gate: unsupported platforms fail before any fetch, demo dir left pristine --------------
# The gate reuses install.sh --print-platform. A refusal must exit non-zero, point the user elsewhere,
# and leave the demo directory holding nothing but the quickstart.sh they downloaded -- zero side effects.
neg_gate() {   # OS ARCH MUSL grep-for label
  run_prepare "$1" "$2" "$3"
  local residue; residue="$(find "$DEMO" -mindepth 1 ! -name quickstart.sh 2>/dev/null)"
  if [ "$RC" -ne 0 ] && printf '%s' "$OUT" | grep -qiE "$4" && [ -z "$residue" ]; then
    ok "$5"
  else
    bad "$5 (rc=$RC, residue='$residue'): $OUT"
  fi
}
neg_gate "MINGW64_NT-10.0" x86_64  glibc 'wsl|source'         "gate refuses Git Bash / MinGW, demo dir pristine"
neg_gate "Linux"           x86_64  musl  'musl'               "gate refuses musl libc, demo dir pristine"
neg_gate "Linux"           riscv64 glibc 'architecture|riscv' "gate refuses an unknown arch, demo dir pristine"

# --- prepare phase: a supported platform downloads everything the demo needs, with no build ----------
run_prepare Linux x86_64 glibc
PREP="$DEMO"
if [ "$RC" -eq 0 ]; then ok "prepare on a supported platform exits 0"; else bad "prepare exits 0 (rc=$RC): $OUT"; fi
have() { if [ -e "$PREP/$1" ]; then ok "$2"; else bad "$2 — missing $1"; fi; }
if [ -x "$PREP/tapstate" ]; then ok "installs the CLI in place as ./tapstate"; else bad "./tapstate not installed/executable: $OUT"; fi
have docker-compose.yml               "fetches the compose file into the demo dir"
have mysql-init/01-orders.sql         "fetches the demo seed SQL"
have mysql-connector.jar              "fetches the mysql connector jar"
have mongodb-connector.jar            "fetches the mongodb connector jar"
# The connector seed dir must exist (compose bind-mounts it) but stay empty: registration goes through
# the CLI upload path, and an empty seed dir is the documented expected case.
if [ -d "$PREP/connectors" ] && [ -z "$(ls -A "$PREP/connectors" 2>/dev/null)" ]; then
  ok "creates an empty connectors/ seed dir (registration is via the CLI, not the seed)"
else
  bad "connectors/ seed dir missing or non-empty: $(ls -A "$PREP/connectors" 2>/dev/null)"
fi

# --- the pinned CLI version: it must resolve with TAPSTATE_VERSION unset, and match the build ---------
# Regression guard for a real defect. Every other check here passes TAPSTATE_VERSION explicitly, so the
# default path -- the only one a clean-machine user takes -- was never exercised. install.sh's default
# resolves the /releases/latest redirect, and GitHub fills that from full releases only; the CLI ships as
# a prerelease, so the lookup returned nothing and install.sh refused, stranding the quickstart at the
# CLI step. quickstart.sh now carries its own pin; an empty TAPSTATE_VERSION exercises that fallback
# exactly as an unset one does (both scripts test with :- / -n).
POM_VERSION="$(sed -n 's/.*<revision>\(.*\)<\/revision>.*/\1/p' "$REPO/pom.xml" | head -1)"
if [ "$VERSION" = "$POM_VERSION" ]; then
  ok "the pinned CLI version matches the build ($VERSION)"
else
  bad "pinned CLI version $VERSION does not match pom.xml revision $POM_VERSION — bump quickstart.sh"
fi

# run_prepare writes the shared RC/OUT/DEMO, and later checks still read the first run's; save and
# restore them so this extra run stays invisible to them.
saved_rc="$RC"; saved_out="$OUT"; saved_demo="$DEMO"
PIN_VERSION="" run_prepare Linux x86_64 glibc
if [ "$RC" -eq 0 ] && [ -x "$DEMO/tapstate" ]; then
  ok "with TAPSTATE_VERSION unset the CLI still installs, from the script's own pin"
else
  bad "with TAPSTATE_VERSION unset the CLI did not install (rc=$RC): $OUT"
fi
unset PIN_VERSION
RC="$saved_rc"; OUT="$saved_out"; DEMO="$saved_demo"

# --- .env: a random admin password, saved with tight perms and announced once ------------------------
# Replaces the shipped admin/admin default so a demo left running is not trivially reachable, and the
# password lives only in a user-readable-only file plus a single line of output -- never a CLI argument
# or shell history.
env_pw="$(sed -n 's/^TAPSTATE_ADMIN_PASSWORD=//p' "$PREP/.env" 2>/dev/null || true)"
if [ -n "$env_pw" ] && [ "$env_pw" != admin ] && [ "${#env_pw}" -ge 16 ]; then
  ok "generates a random admin password in .env (not the shipped default)"
else
  bad ".env password weak or absent: '$env_pw'"
fi
# ls -l is the portable way to read the mode string here (macOS find has no -printf).
# shellcheck disable=SC2012
perms="$(ls -l "$PREP/.env" 2>/dev/null | awk '{print $1}')"
case "$perms" in
  -rw-------*) ok "writes .env readable only by the user (600)" ;;
  *)          bad ".env perms = '$perms', want -rw-------" ;;
esac
count="$(printf '%s\n' "$OUT" | grep -Fc "$env_pw" || true)"
if [ "$count" = 1 ]; then ok "announces the password exactly once"; else bad "password printed $count times (want 1)"; fi
# two fresh runs get different passwords -- a randomness proxy that a fixed or predictable value fails
run_prepare Darwin arm64 glibc; pw_a="$(sed -n 's/^TAPSTATE_ADMIN_PASSWORD=//p' "$DEMO/.env" 2>/dev/null || true)"
run_prepare Darwin arm64 glibc; pw_b="$(sed -n 's/^TAPSTATE_ADMIN_PASSWORD=//p' "$DEMO/.env" 2>/dev/null || true)"
if [ -n "$pw_a" ] && [ "$pw_a" != "$pw_b" ]; then ok "each fresh run gets a different password"; else bad "passwords not distinct: '$pw_a' vs '$pw_b'"; fi

# --- the demo workspace is generated, uses in-network addresses, and honours the CEL constraint ------
WORK="$PREP/work"
have work/source/db_src.tap.yml        "generates the source resource"
have work/source/warehouse.tap.yml     "generates the target resource"
have work/pipeline/sync_orders.tap.yml "generates the pipeline resource"
# Addresses use compose service names: the connector runs inside the server container, so 127.0.0.1
# would point the server at itself, not at the databases.
if grep -q 'host: mysql' "$WORK/source/db_src.tap.yml" 2>/dev/null && ! grep -q '127.0.0.1' "$WORK/source/db_src.tap.yml" 2>/dev/null; then
  ok "source addresses mysql by its compose service name, not loopback"
else
  bad "source is not addressed by service name: $(cat "$WORK/source/db_src.tap.yml" 2>/dev/null)"
fi
if grep -q 'mongo:27017' "$WORK/source/warehouse.tap.yml" 2>/dev/null && ! grep -q '127.0.0.1' "$WORK/source/warehouse.tap.yml" 2>/dev/null; then
  ok "target addresses mongo by its compose service name, not loopback"
else
  bad "target is not addressed by service name: $(cat "$WORK/source/warehouse.tap.yml" 2>/dev/null)"
fi
vcount="$(cat "$WORK"/source/*.tap.yml "$WORK"/pipeline/*.tap.yml 2>/dev/null | grep -c '^version: tapstate/v1' || true)"
if [ "$vcount" = 3 ]; then ok "all three resources declare version: tapstate/v1"; else bad "version lines = $vcount (want 3)"; fi
# HARD CONSTRAINT: the decimal `amount` column cannot pass through a CEL expression in this preview, so
# the demo pipeline must never name it -- it only ever passes through untouched.
if ! grep -q 'amount' "$WORK/pipeline/sync_orders.tap.yml" 2>/dev/null \
   && ! grep -qE '=[^#]*after\.(id|amount)' "$WORK/pipeline/sync_orders.tap.yml" 2>/dev/null; then
  ok "the decimal amount column never appears in the pipeline (no numeric column in any CEL)"
else
  bad "a numeric column leaked into the pipeline: $(grep -nE 'amount|after\.(id|amount)' "$WORK/pipeline/sync_orders.tap.yml" 2>/dev/null)"
fi
# there IS a CEL, and it reads only string/envelope fields -- proves the demo still exercises transforms
if grep -q '"=after.customer' "$WORK/pipeline/sync_orders.tap.yml" 2>/dev/null; then
  ok "the demo still exercises a CEL transform over string/envelope fields"
else
  bad "no string/envelope CEL present: $(cat "$WORK/pipeline/sync_orders.tap.yml" 2>/dev/null)"
fi

# --- idempotency: a re-run keeps generated state and does not re-download verified assets ------------
run_prepare Linux x86_64 glibc; RE="$DEMO"
pw_before="$(sed -n 's/^TAPSTATE_ADMIN_PASSWORD=//p' "$RE/.env")"
printf '# user-marker\n' >> "$RE/docker-compose.yml"   # a marker a re-download would erase
printf 'user-edit\n' > "$RE/work/marker"               # a user edit to the workspace
run_prepare Linux x86_64 glibc "$RE"                   # re-run over the same dir
pw_after="$(sed -n 's/^TAPSTATE_ADMIN_PASSWORD=//p' "$RE/.env")"
if [ "$RC" -eq 0 ] \
   && [ -n "$pw_before" ] && [ "$pw_before" = "$pw_after" ] \
   && [ -f "$RE/work/marker" ] \
   && grep -q '# user-marker' "$RE/docker-compose.yml"; then
  ok "a re-run keeps the password and workspace, and does not re-download assets"
else
  bad "re-run not idempotent (rc=$RC, pw_same=$([ "$pw_before" = "$pw_after" ] && echo y || echo n), work_marker=$([ -f "$RE/work/marker" ] && echo y || echo n), compose_marker=$(grep -q '# user-marker' "$RE/docker-compose.yml" && echo y || echo n))"
fi

# --- macOS quarantine: the strip fires on Darwin, and only there -------------------------------------
run_prepare Darwin arm64 glibc; DARWIN="$DEMO"
if [ -f "$DARWIN/.xattr-calls" ] && grep -q 'com.apple.quarantine' "$DARWIN/.xattr-calls" && grep -q 'tapstate' "$DARWIN/.xattr-calls"; then
  ok "strips the macOS quarantine attribute from the CLI on Darwin"
else
  bad "quarantine not stripped on Darwin: $(cat "$DARWIN/.xattr-calls" 2>/dev/null)"
fi
run_prepare Linux x86_64 glibc; LINUX="$DEMO"
if [ ! -f "$LINUX/.xattr-calls" ]; then
  ok "does not touch xattr on Linux (the strip is macOS-only)"
else
  bad "xattr called on Linux: $(cat "$LINUX/.xattr-calls" 2>/dev/null)"
fi

# --- run phase (fakes): the live stack is the end-to-end test's to prove; here fakes pin what must not -
# regress -- the password reaches the CLI over stdin (never an argument), the online verbs are driven,
# and teardown is printed. A recording ./tapstate replaces the stub after prepare; a fake docker reports
# the server healthy so the wait ends. curl stays real, so the gate's install.sh fetch still works.
run_phase_fakes() {
  run_prepare Linux x86_64 glibc            # prepare a dir (installs the stub CLI)
  RUN="$DEMO"
  RUN_PW="$(sed -n 's/^TAPSTATE_ADMIN_PASSWORD=//p' "$RUN/.env")"
  cat > "$RUN/tapstate" <<'CLI'
#!/bin/sh
printf '%s\n' "$*" >> .cli-argv
cat >> .cli-stdin
exit 0
CLI
  chmod +x "$RUN/tapstate"
  local shim; shim="$(mktemp -d)"
  # the $1 is the fake uname's own argument -- it must stay literal.
  # shellcheck disable=SC2016
  printf '#!/bin/sh\ncase "$1" in -s) echo Linux ;; -m) echo x86_64 ;; *) echo unknown ;; esac\n' > "$shim/uname"
  cat > "$shim/docker" <<'DOCK'
#!/bin/sh
# `compose ps ... server` -> report healthy so the wait loop ends; `compose exec ... mongosh` (the
# snapshot count read) -> report a row count so the verify loop ends; every other subcommand no-ops.
for a in "$@"; do
  [ "$a" = ps ] && { echo '{"Health":"healthy"}'; exit 0; }
  [ "$a" = exec ] && { echo 5; exit 0; }
done
exit 0
DOCK
  chmod +x "$shim/uname" "$shim/docker"
  RUN_OUT="$(cd "$RUN" && PATH="$shim:$PATH" \
    TAPSTATE_VERSION="$VERSION" TAPSTATE_BASE_URL="file://$CLI_STUB" \
    TAPSTATE_QUICKSTART_BASE_URL="file://$QS_STUB" TAPSTATE_CONNECTORS_URL="file://$QS_STUB/connectors-preview" \
    sh "$RUN/quickstart.sh" 2>&1)"; RUN_RC=$?
  rm -rf "$shim"
}
run_phase_fakes
if [ "$RUN_RC" -eq 0 ] && [ -f "$RUN/.cli-argv" ] && ! grep -Fq "$RUN_PW" "$RUN/.cli-argv" && grep -Fq "$RUN_PW" "$RUN/.cli-stdin"; then
  ok "the admin password reaches the CLI over stdin, never as a command argument"
else
  bad "password handling (rc=$RUN_RC, argv=$(grep -Fq "$RUN_PW" "$RUN/.cli-argv" 2>/dev/null && echo LEAK || echo ok), stdin=$(grep -Fq "$RUN_PW" "$RUN/.cli-stdin" 2>/dev/null && echo ok || echo MISSING)): $RUN_OUT"
fi
if grep -q 'register \.\./mysql-connector.jar' "$RUN/.cli-stdin" 2>/dev/null && grep -q '^apply' "$RUN/.cli-stdin" 2>/dev/null && grep -q 'start sync_orders' "$RUN/.cli-stdin" 2>/dev/null; then
  ok "drives register / apply / start through the REPL"
else
  bad "online verbs not driven: $(cat "$RUN/.cli-stdin" 2>/dev/null)"
fi
if printf '%s' "$RUN_OUT" | grep -q 'down -v' && printf '%s' "$RUN_OUT" | grep -q 'rm -rf'; then
  ok "prints teardown on completion (down -v + rm -rf, images noted)"
else
  bad "no teardown printed: $RUN_OUT"
fi
# The snapshot payoff is a real row count, printed with no user action (the fake docker returns 5).
if printf '%s' "$RUN_OUT" | grep -q 'the target now holds 5 rows'; then
  ok "prints the snapshot row count automatically (no user action)"
else
  bad "snapshot row count not printed: $RUN_OUT"
fi
# The CDC section walks all three operations -- consistent with a pipeline that no longer drops deletes.
if printf '%s' "$RUN_OUT" | grep -q 'INSERT INTO orders' \
   && printf '%s' "$RUN_OUT" | grep -q 'UPDATE orders' \
   && printf '%s' "$RUN_OUT" | grep -q 'DELETE FROM orders'; then
  ok "the CDC section demonstrates insert, update and delete"
else
  bad "CDC section does not walk insert/update/delete: $RUN_OUT"
fi

# --- what the release serves: one pinned version, and a stack that pulls rather than builds ----------
# The demo directory a user lands in is not a checkout. Two things follow, and neither can be left as a
# step someone performs at release time -- an omitted step here does not fail the release, it fails the
# user, weeks later, on a machine nobody is watching.
#
# First, the assets the script fetches must come from the same release as the CLI it pins. Pointing the
# base at a branch would hand out a CLI frozen at one version alongside a compose file that keeps moving,
# and the mismatch would appear only on the user's machine. Deriving the base from the pin means the
# release tag is the single thing that decides, and the pin is already checked against the build above.
# Both patterns below are read as text, not evaluated: the point is that the source carries an
# unexpanded ${CLI_VERSION}, so a literal is what must be matched.
# shellcheck disable=SC2016
DEFAULT_QBASE="$(sed -n 's/^ *qbase="\${TAPSTATE_QUICKSTART_BASE_URL:-\(.*\)}"$/\1/p' "$QUICKSTART_SH")"
# shellcheck disable=SC2016
case "$DEFAULT_QBASE" in
  *'${CLI_VERSION}'*)
    ok "the default asset base is derived from the pinned CLI version, not a branch" ;;
  *)
    bad "default asset base '$DEFAULT_QBASE' is not derived from CLI_VERSION — a branch keeps moving after the release" ;;
esac

# Second, the compose file must name a published image. A `build:` key is unusable from a demo directory:
# its context points into a repository that is not there. The source path keeps its build through an
# explicit override file instead, so the released stack and the development stack stop being the same
# file trying to be both.
COMPOSE="$HERE/docker-compose.yml"
COMPOSE_DEV="$HERE/docker-compose.dev.yml"
if grep -qE '^\s*build:' "$COMPOSE"; then
  bad "docker-compose.yml still carries a build: — a demo directory has no repository to build from"
else
  ok "the released compose file has no build: (a demo directory cannot build)"
fi
if grep -qE '^\s*image:\s*ghcr\.io/' "$COMPOSE"; then
  ok "the released compose file pins a published registry image"
else
  bad "docker-compose.yml does not pin a ghcr.io image: $(grep -nE '^\s*image:' "$COMPOSE" | tr '\n' ' ')"
fi
# The image tag drifting from the build is the same defect as the CLI pin drifting, and gets the same guard.
COMPOSE_TAG="$(sed -n 's|.*image:.*ghcr\.io/[^:]*:\(.*\)|\1|p' "$COMPOSE" | sed 's/}$//; s/.*:-//')"
if [ "$COMPOSE_TAG" = "$POM_VERSION" ]; then
  ok "the compose image tag matches the build ($COMPOSE_TAG)"
else
  bad "compose image tag '$COMPOSE_TAG' does not match pom.xml revision $POM_VERSION"
fi
if [ -f "$COMPOSE_DEV" ] && grep -qE '^\s*build:' "$COMPOSE_DEV"; then
  ok "the development override re-adds the build for the from-source path"
else
  bad "docker-compose.dev.yml missing or carries no build: — the from-source path would have no way to build"
fi

# --- summary ----------------------------------------------------------------------------------------
echo
printf '\033[1mquickstart smoke: %d passed, %d failed\033[0m\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
