#!/usr/bin/env bash
#
# Test harness for install/install.sh. Exercises the installer as a black box against a local file://
# stub release tree, so no network is touched: platform detection and its four-tuple mapping, the
# unsupported-platform refusals (Windows/musl/unknown — the AC17 negatives, which must exit non-zero and
# leave no binary behind), sha256 verification (a mismatch must refuse), the TAPSTATE_INSTALL_DIR seam,
# and an idempotent re-run. A fake `uname` (and, for the musl case, a fake `ldd`) placed first on PATH
# drives the platform each run sees. Exit 0 iff every check passes.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
INSTALL_SH="$HERE/install.sh"
VERSION=0.1.0

PASS=0; FAIL=0
ok()  { printf '  PASS  %s\n' "$1"; PASS=$((PASS + 1)); }
bad() { printf '  FAIL  %s\n' "$1"; FAIL=$((FAIL + 1)); }

sha256_of() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1"; else shasum -a 256 "$1"; fi; }

# --- a stub release tree: <stub>/download/v<ver>/tapstate-<ver>-<platform>.tar.gz (+ .sha256) --------
STUB="$(mktemp -d)"
trap 'rm -rf "$STUB"' EXIT
make_asset() {   # $1 = platform label; the fake binary echoes its platform so a test can prove the mapping
  platform="$1"
  d="$STUB/download/v$VERSION"; mkdir -p "$d"
  stage="$(mktemp -d)"
  printf '#!/bin/sh\necho "tapstate %s %s"\n' "$VERSION" "$platform" > "$stage/tapstate"
  chmod +x "$stage/tapstate"
  echo license > "$stage/LICENSE"; echo notice > "$stage/NOTICE"
  asset="tapstate-$VERSION-$platform.tar.gz"
  tar -czf "$d/$asset" -C "$stage" tapstate LICENSE NOTICE
  ( cd "$d" && sha256_of "$asset" > "$asset.sha256" )
  rm -rf "$stage"
}
for p in darwin-arm64 darwin-x64 linux-x64 linux-arm64; do make_asset "$p"; done

# run install.sh seeing a fake platform. args: OS ARCH MUSL(glibc|musl) INSTALL_DIR ; sets RC + OUT
run_install() {
  local fos="$1" farch="$2" fmusl="$3" idir="$4" shim
  shim="$(mktemp -d)"
  cat > "$shim/uname" <<EOF
#!/bin/sh
case "\$1" in
  -s) echo "$fos" ;;
  -m) echo "$farch" ;;
  *)  echo unknown ;;
esac
EOF
  chmod +x "$shim/uname"
  if [ "$fmusl" = musl ]; then
    printf '#!/bin/sh\necho "musl libc (x86_64)\\nVersion 1.2.4"\n' > "$shim/ldd"
    chmod +x "$shim/ldd"
  fi
  OUT="$(PATH="$shim:$PATH" \
         TAPSTATE_VERSION="$VERSION" \
         TAPSTATE_BASE_URL="file://$STUB" \
         TAPSTATE_INSTALL_DIR="$idir" \
         sh "$INSTALL_SH" 2>&1)"
  RC=$?
  rm -rf "$shim"
}

printf '\033[1minstall smoke — %s\033[0m\n' "$INSTALL_SH"

# --- positive: the four supported tuples map correctly and install a runnable binary ----------------
for triple in "Darwin arm64 darwin-arm64" "Darwin x86_64 darwin-x64" "Linux x86_64 linux-x64" "Linux aarch64 linux-arm64"; do
  # shellcheck disable=SC2086
  set -- $triple
  idir="$(mktemp -d)/bin"
  run_install "$1" "$2" glibc "$idir"
  if [ "$RC" -eq 0 ] && [ -x "$idir/tapstate" ] && "$idir/tapstate" | grep -q "tapstate $VERSION $3"; then
    ok "maps $1/$2 -> $3 and installs a runnable binary into TAPSTATE_INSTALL_DIR"
  else
    bad "install $1/$2 (want $3) rc=$RC: $OUT"
  fi
done

# --- idempotent: a second run over the same dir upgrades in place, still exit 0 ----------------------
idir="$(mktemp -d)/bin"
run_install Darwin arm64 glibc "$idir"; rc1=$RC
run_install Darwin arm64 glibc "$idir"; rc2=$RC
if [ "$rc1" -eq 0 ] && [ "$rc2" -eq 0 ] && [ -x "$idir/tapstate" ]; then
  ok "re-run over the same install dir is idempotent (exit 0, binary intact)"
else
  bad "re-run not idempotent (rc1=$rc1 rc2=$rc2): $OUT"
fi

# --- AC17 negatives: unsupported platforms refuse, non-zero, no binary left behind ------------------
neg() {   # OS ARCH MUSL grep-for label
  local idir; idir="$(mktemp -d)/bin"
  run_install "$1" "$2" "$3" "$idir"
  if [ "$RC" -ne 0 ] && printf '%s' "$OUT" | grep -qiE "$4" && [ ! -e "$idir/tapstate" ]; then
    ok "$5"
  else
    bad "$5 (rc=$RC, binary present=$( [ -e "$idir/tapstate" ] && echo yes || echo no )): $OUT"
  fi
}
neg "MINGW64_NT-10.0" x86_64  glibc 'wsl|source'         "refuses Git Bash / MinGW (points to WSL or source)"
neg "MSYS_NT-10.0"    x86_64  glibc 'wsl|source'         "refuses MSYS2 (points to WSL or source)"
neg "CYGWIN_NT-10.0"  x86_64  glibc 'wsl|source'         "refuses Cygwin (points to WSL or source)"
neg "Linux"           x86_64  musl  'musl'               "refuses musl libc (Alpine)"
neg "Linux"           riscv64 glibc 'architecture|riscv' "refuses an unknown architecture, never guesses"
neg "SunOS"           x86_64  glibc 'system|sunos'       "refuses an unknown OS, never guesses"

# --- sha256 verification: a mismatch refuses (does not silently install) -----------------------------
echo "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef  tapstate-$VERSION-darwin-arm64.tar.gz" \
  > "$STUB/download/v$VERSION/tapstate-$VERSION-darwin-arm64.tar.gz.sha256"
idir="$(mktemp -d)/bin"
run_install Darwin arm64 glibc "$idir"
if [ "$RC" -ne 0 ] && [ ! -e "$idir/tapstate" ] && printf '%s' "$OUT" | grep -qiE 'checksum|sha256|verif'; then
  ok "refuses to install when the sha256 does not match the download"
else
  bad "sha256 mismatch not refused (rc=$RC): $OUT"
fi
make_asset darwin-arm64   # restore the good checksum

# --- latest-version resolution over HTTP: no TAPSTATE_VERSION -> the /releases/latest 302 -----------
# file:// cannot redirect, so this path needs a tiny HTTP stub: /releases/latest 302s to the tag URL,
# /releases/tag/* answers 200 (so the redirect-following curl -f is happy), and /releases/download/*
# serves the staged assets. The stub double-forks and publishes its port + pid (no startup sleep race).
if command -v python3 >/dev/null 2>&1; then
  cat > "$STUB/httpstub.py" <<'PY'
import http.server, os, socketserver, sys
root, ver = sys.argv[1], sys.argv[2]
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        host, port = self.server.server_address
        if self.path == "/releases/latest":
            self.send_response(302)
            self.send_header("Location", "http://%s:%d/releases/tag/v%s" % (host, port, ver))
            self.end_headers(); return
        if self.path.startswith("/releases/tag/"):
            self.send_response(200); self.send_header("Content-Length", "0"); self.end_headers(); return
        if self.path.startswith("/releases/download/"):
            local = root + self.path[len("/releases"):]
            if os.path.isfile(local):
                data = open(local, "rb").read()
                self.send_response(200); self.send_header("Content-Length", str(len(data))); self.end_headers()
                self.wfile.write(data); return
        self.send_response(404); self.end_headers()
    def log_message(self, *a):
        pass
srv = socketserver.TCPServer(("127.0.0.1", 0), H)
with open(sys.argv[3], "w") as f:
    f.write(str(srv.server_address[1]))
if os.fork() > 0:
    os._exit(0)
os.setsid()
with open(sys.argv[4], "w") as f:
    f.write(str(os.getpid()))
srv.serve_forever()
PY
  python3 "$STUB/httpstub.py" "$STUB" "$VERSION" "$STUB/port" "$STUB/pid"
  port="$(cat "$STUB/port")"
  shim="$(mktemp -d)"
  # the $1 is the fake uname script's own argument — it must stay literal here.
  # shellcheck disable=SC2016
  printf '#!/bin/sh\ncase "$1" in -s) echo Darwin ;; -m) echo arm64 ;; *) echo unknown ;; esac\n' > "$shim/uname"
  chmod +x "$shim/uname"
  idir="$(mktemp -d)/bin"
  out="$(PATH="$shim:$PATH" TAPSTATE_BASE_URL="http://127.0.0.1:$port/releases" TAPSTATE_INSTALL_DIR="$idir" sh "$INSTALL_SH" 2>&1)"; rc=$?
  [ -f "$STUB/pid" ] && kill "$(cat "$STUB/pid")" 2>/dev/null || true
  rm -rf "$shim"
  if [ "$rc" -eq 0 ] && [ -x "$idir/tapstate" ] && printf '%s' "$out" | grep -q "tapstate $VERSION"; then
    ok "resolves the latest version via the /releases/latest 302 (no version pinned) and installs over HTTP"
  else
    bad "latest-version resolution failed (rc=$rc): $out"
  fi
else
  printf '  SKIP  latest-302 resolution (python3 not available)\n'
fi

# --- summary ----------------------------------------------------------------------------------------
echo
printf '\033[1minstall smoke: %d passed, %d failed\033[0m\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
