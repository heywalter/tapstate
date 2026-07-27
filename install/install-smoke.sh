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

# run install.sh seeing a fake platform. args: OS ARCH MUSL(glibc|musl) INSTALL_DIR [MACOS_VERSION]
# A fifth argument installs a fake `sw_vers` reporting that macOS product version, which drives the
# minimum-version gate. Leaving it empty means the run sees whatever sw_vers this machine has (on Linux,
# none at all) -- the same as every test written before the gate existed.
run_install() {
  local fos="$1" farch="$2" fmusl="$3" idir="$4" fmacos="${5:-}" shim
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
  if [ -n "$fmacos" ]; then
    printf '#!/bin/sh\necho "%s"\n' "$fmacos" > "$shim/sw_vers"
    chmod +x "$shim/sw_vers"
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

# --- detect-only: --print-platform maps and prints the tuple, downloading and writing nothing -------
# The demo bootstrap (quickstart.sh) reuses this to gate on the platform before it fetches anything, so
# an unsupported platform leaves the working directory untouched. It must print only the tuple, need no
# network (no version resolution), and create no install directory.
detect_shim="$(mktemp -d)"
# shellcheck disable=SC2016
printf '#!/bin/sh\ncase "$1" in -s) echo Darwin ;; -m) echo arm64 ;; *) echo unknown ;; esac\n' > "$detect_shim/uname"
chmod +x "$detect_shim/uname"
idir="$(mktemp -d)/bin"
out="$(PATH="$detect_shim:$PATH" TAPSTATE_INSTALL_DIR="$idir" sh "$INSTALL_SH" --print-platform 2>&1)"; rc=$?
if [ "$rc" -eq 0 ] && [ "$out" = darwin-arm64 ] && [ ! -e "$idir/tapstate" ] && [ ! -d "$idir" ]; then
  ok "--print-platform prints the tuple and downloads/writes nothing"
else
  bad "--print-platform (rc=$rc, out='$out', install dir present=$( [ -e "$idir" ] && echo yes || echo no ))"
fi
# and unsupported platforms still fail loudly in detect-only mode, pointing the user elsewhere
cat > "$detect_shim/uname" <<'EOF'
#!/bin/sh
case "$1" in -s) echo MINGW64_NT-10.0 ;; -m) echo x86_64 ;; *) echo unknown ;; esac
EOF
chmod +x "$detect_shim/uname"
idir="$(mktemp -d)/bin"
out="$(PATH="$detect_shim:$PATH" TAPSTATE_INSTALL_DIR="$idir" sh "$INSTALL_SH" --print-platform 2>&1)"; rc=$?
rm -rf "$detect_shim"
if [ "$rc" -ne 0 ] && [ ! -e "$idir" ] && printf '%s' "$out" | grep -qiE 'wsl|source'; then
  ok "--print-platform on an unsupported platform fails loudly and writes nothing"
else
  bad "--print-platform unsupported (rc=$rc, out='$out')"
fi

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
  if [ -f "$STUB/pid" ]; then kill "$(cat "$STUB/pid")" 2>/dev/null || true; fi
  rm -rf "$shim"
  if [ "$rc" -eq 0 ] && [ -x "$idir/tapstate" ] && printf '%s' "$out" | grep -q "tapstate $VERSION"; then
    ok "resolves the latest version via the /releases/latest 302 (no version pinned) and installs over HTTP"
  else
    bad "latest-version resolution failed (rc=$rc): $out"
  fi
else
  printf '  SKIP  latest-302 resolution (python3 not available)\n'
fi

# --- the recommended macOS version: said out loud, never enforced -----------------------------------
# A native binary carries the deployment target of the machine that built it, so an older macOS may not
# load it -- and when that happens it happens at launch, from dyld, far from the install that caused it.
# So the installer says so. It does not refuse: unlike the platforms above, a binary for this one exists,
# and whether to try it is the user's call. Every case below therefore asserts the install *succeeded*;
# what varies is only whether the notice was printed. The recommendation belongs to the release (build
# machines move between releases), so it is published alongside the assets and read from there.
MINIMUMS="$STUB/download/v$VERSION/platform-minimums.txt"
FLOOR=
set_floor() { FLOOR="$1"; printf 'darwin-arm64 macos %s\ndarwin-x64 macos %s\n' "$1" "$1" > "$MINIMUMS"; }

# Detect the notice by a phrase only it carries. Matching on the version alone would be fooled by a
# temp path that happens to contain the same digits.
noticed() { printf '%s' "$OUT" | grep -q 'may not launch'; }

say() {   # MACOS_VERSION EXPECT(notice|quiet) LABEL
  local idir said; idir="$(mktemp -d)/bin"
  run_install Darwin arm64 glibc "$idir" "$1"
  if [ "$RC" -ne 0 ] || [ ! -x "$idir/tapstate" ]; then
    bad "$3 -- the install must never be refused (rc=$RC): $OUT"; return
  fi
  if noticed; then said=notice; else said=quiet; fi
  if [ "$said" != "$2" ]; then
    bad "$3 (wanted $2, got $said): $OUT"; return
  fi
  # a notice that does not name both versions leaves the reader to guess which macOS this needs
  if [ "$2" = notice ] && ! { printf '%s' "$OUT" | grep -qF "$FLOOR" && printf '%s' "$OUT" | grep -qF "$1"; }; then
    bad "$3 -- notice names neither the recommendation nor the running version: $OUT"; return
  fi
  ok "$3"
}

set_floor 15.0
say 14.7 notice "says so below the recommended version, and installs anyway, naming both versions"
say 15.0 quiet  "stays quiet on exactly the recommended version"
say 15.5 quiet  "stays quiet on a newer macOS in the same major"
say 26.1 quiet  "stays quiet on a higher major -- the version the next runner generation will publish"

# Version fields are numbers, not text, and both directions of getting that wrong are covered. Compared
# as text, 15.9 sorts above 15.10 -- so the machine that most needs telling would hear nothing -- and a
# bare "15" sorts below "15.0", which would nag a machine sitting exactly on the recommendation. Neither
# is hypothetical: macOS reports both shapes, and minor versions do reach double digits.
set_floor 15.10
say 15.9 notice "says so for 15.9 against a 15.10 recommendation (fields compared as numbers, not as text)"
set_floor 15.0
say 15 quiet "stays quiet on a bare major equal to the recommendation (an absent field counts as zero)"

# Each platform carries its own recommendation, and the two darwin legs are built on separate machines
# that need not move in step. The line is selected by the platform tuple, not by being first in the file.
printf 'darwin-arm64 macos 15.0\ndarwin-x64 macos 26.0\n' > "$MINIMUMS"
idir="$(mktemp -d)/bin"
run_install Darwin x86_64 glibc "$idir" 15.5
if [ "$RC" -eq 0 ] && [ -x "$idir/tapstate" ] && noticed && printf '%s' "$OUT" | grep -qF 26.0; then
  ok "reads the recommendation of the platform being installed, not whichever line comes first"
else
  bad "platform-keyed lookup (rc=$RC, noticed=$(noticed && echo yes || echo no)): $OUT"
fi
idir="$(mktemp -d)/bin"
run_install Darwin arm64 glibc "$idir" 15.5
if [ "$RC" -eq 0 ] && [ -x "$idir/tapstate" ] && ! noticed; then
  ok "the same file leaves arm64 at 15.5 alone, whose own recommendation is lower"
else
  bad "arm64 nagged by the x64 recommendation (rc=$RC): $OUT"
fi

# a macOS recommendation says nothing about Linux, which has no sw_vers and no entry in the file
idir="$(mktemp -d)/bin"
run_install Linux x86_64 glibc "$idir"
if [ "$RC" -eq 0 ] && [ -x "$idir/tapstate" ] && ! noticed; then
  ok "a macOS recommendation does not reach a Linux install"
else
  bad "linux install saw the macOS notice (rc=$RC): $OUT"
fi

# a release that publishes no minimums has nothing to compare against, and must not invent one
rm -f "$MINIMUMS"
idir="$(mktemp -d)/bin"
run_install Darwin arm64 glibc "$idir" 14.7
if [ "$RC" -eq 0 ] && [ -x "$idir/tapstate" ] && ! noticed; then
  ok "a release without published minimums installs silently (nothing to compare against)"
else
  bad "missing minimums must produce no notice (rc=$RC): $OUT"
fi

# --- summary ----------------------------------------------------------------------------------------
echo
printf '\033[1minstall smoke: %d passed, %d failed\033[0m\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
