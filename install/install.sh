#!/bin/sh
#
# tapstate installer — downloads the prebuilt native CLI for this platform, verifies it, and drops it
# into a bin directory. It never uses sudo, never edits a shell rc file, and never guesses: an
# unsupported platform (Windows shells, musl libc, or an unknown OS/arch) fails loudly before anything
# is downloaded, so nothing is left behind.
#
# Usage (download-then-run, or piped):
#   sh install.sh
#
# Environment seams:
#   TAPSTATE_VERSION       pin a version (e.g. 0.1.0); default is the latest release, found via the web
#                          redirect on /releases/latest (no GitHub API call).
#   TAPSTATE_INSTALL_DIR   where to place the binary; default $HOME/.tapstate/bin. This is the seam the
#                          demo bootstrap reuses to install in place (TAPSTATE_INSTALL_DIR=.), so the
#                          binary never enters PATH and `rm -rf` of the demo directory removes it.
#   TAPSTATE_BASE_URL      release base URL; default https://github.com/tapstate/tapstate/releases
#                          (override for a mirror).
#
# POSIX sh, no bashisms. All work is inside main(); the final line calls it, so a truncated download can
# never execute a partial script.
set -eu

die() {
    printf 'install: %s\n' "$1" >&2
    exit 1
}

# True when this Linux uses musl (Alpine and similar): the binaries are glibc-linked, so musl is refused.
# musl ships its own dynamic loader, and its ldd banner names it; either signal alone is conclusive.
is_musl() {
    for f in /lib/ld-musl-*.so.1; do
        [ -e "$f" ] && return 0
    done
    if command -v ldd >/dev/null 2>&1 && ldd --version 2>&1 | grep -qi musl; then
        return 0
    fi
    return 1
}

# Map uname to the release's <os>-<arch> tuple, or refuse. Runs before anything touches the filesystem.
detect_platform() {
    os="$(uname -s)"
    arch="$(uname -m)"
    case "$os" in
        Darwin) os_label=darwin ;;
        Linux)
            if is_musl; then
                die "musl libc (e.g. Alpine) is not supported; the binaries are built for glibc. Use a glibc-based system, or build from source."
            fi
            os_label=linux
            ;;
        MINGW* | MSYS* | CYGWIN*)
            die "Windows shells (Git Bash / MSYS2 / Cygwin) are not supported. Use WSL2, or build from source." ;;
        *)
            die "unsupported operating system '$os'. Supported: macOS (Darwin), Linux (glibc). Build from source for others." ;;
    esac
    case "$arch" in
        arm64 | aarch64) arch_label=arm64 ;;
        x86_64 | amd64) arch_label=x64 ;;
        *)
            die "unsupported CPU architecture '$arch'. Supported: arm64/aarch64, x86_64/amd64. Build from source for others." ;;
    esac
    platform="${os_label}-${arch_label}"
}

# Download $1 to the file $2 with whichever of curl / wget is present.
fetch() {
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$1" -o "$2"
    elif command -v wget >/dev/null 2>&1; then
        wget -q "$1" -O "$2"
    else
        die "neither curl nor wget is available to download $1."
    fi
}

# The version to install: the pinned one, or the latest, discovered from the /releases/latest redirect
# (the effective URL ends /releases/tag/v<version>) rather than the GitHub API.
resolve_version() {
    if [ -n "${TAPSTATE_VERSION:-}" ]; then
        version="$TAPSTATE_VERSION"
        return
    fi
    command -v curl >/dev/null 2>&1 || die "auto-detecting the latest version needs curl; set TAPSTATE_VERSION to pin one."
    effective="$(curl -fsSL -o /dev/null -w '%{url_effective}' "${base_url}/latest")"
    version="$(printf '%s' "$effective" | sed -n 's#.*/tag/v\([^/]*\)$#\1#p')"
    [ -n "$version" ] || die "could not determine the latest version from ${base_url}/latest; set TAPSTATE_VERSION to pin one."
}

# True when $1 is a dotted decimal version and nothing else, so the comparison below never feeds a word
# to an integer test. Anything else is treated as unparseable and leaves the check skipped.
is_dotted_number() {
    case "$1" in
        '' | *[!0-9.]*) return 1 ;;
        *) return 0 ;;
    esac
}

# True when dotted version $1 is at least $2, compared field by field as integers. A string comparison
# would put 26.1 below 15.0 and refuse exactly the machines a newer build generation targets. Absent
# fields count as zero, so 15 and 15.0.0 compare equal.
version_ge() {
    h="$1"
    n="$2"
    while [ -n "$h" ] || [ -n "$n" ]; do
        case "$h" in *.*) hf="${h%%.*}"; h="${h#*.}" ;; *) hf="$h"; h="" ;; esac
        case "$n" in *.*) nf="${n%%.*}"; n="${n#*.}" ;; *) nf="$n"; n="" ;; esac
        [ -n "$hf" ] || hf=0
        [ -n "$nf" ] || nf=0
        if [ "$hf" -gt "$nf" ]; then
            return 0
        fi
        if [ "$hf" -lt "$nf" ]; then
            return 1
        fi
    done
    return 0
}

# Refuse a macOS too old to load this release's binary, before the binary is fetched. A native binary
# carries the deployment target of the machine that built it, and the hosted build machines move between
# releases, so the floor belongs to the release rather than to this script: it is read from the release's
# own platform-minimums.txt (lines of "<platform> <requirement> <version>"). Enforcement is dyld's, at
# load time, with nothing the user can do about it -- which makes an older macOS an unsupported platform
# like any other, and puts the refusal here with the rest of them. Anything that cannot be checked -- a
# release that publishes no such file, an unreadable version -- is left to install as before, because a
# check that did not happen must not masquerade as one that passed.
check_minimum_os() {
    [ "$os_label" = darwin ] || return 0
    command -v sw_vers >/dev/null 2>&1 || return 0
    fetch "${base_url}/download/v${version}/platform-minimums.txt" "$tmp/minimums" || return 0
    [ -s "$tmp/minimums" ] || return 0
    need="$(awk -v p="$platform" '$1 == p && $2 == "macos" { print $3 }' "$tmp/minimums")"
    [ -n "$need" ] || return 0
    have="$(sw_vers -productVersion)"
    is_dotted_number "$need" || return 0
    is_dotted_number "$have" || return 0
    if version_ge "$have" "$need"; then
        return 0
    fi
    die "tapstate $version requires macOS $need or newer; this machine runs $have, where the binary cannot load. Upgrade macOS, or build from source."
}

# Refuse to install unless the download's sha256 matches its published checksum. No tool = refuse, never skip.
verify_sha256() {
    file="$1"
    sumfile="$2"
    if command -v sha256sum >/dev/null 2>&1; then
        actual="$(sha256sum "$file" | awk '{print $1}')"
    elif command -v shasum >/dev/null 2>&1; then
        actual="$(shasum -a 256 "$file" | awk '{print $1}')"
    else
        die "no sha256 tool (sha256sum or shasum) is available; refusing to install an unverified download."
    fi
    expected="$(awk '{print $1}' "$sumfile")"
    [ -n "$expected" ] || die "the checksum file is empty or malformed; refusing to install."
    if [ "$expected" != "$actual" ]; then
        die "sha256 checksum mismatch for $(basename "$file") (expected $expected, got $actual); refusing to install."
    fi
}

# Extract the archive and atomically place the binary. Staging inside the destination makes the final
# rename a same-filesystem move, so a reader never sees a half-written binary; a re-run just replaces it.
install_binary() {
    tar -xzf "$tmp/$asset" -C "$tmp"
    [ -f "$tmp/tapstate" ] || die "the downloaded archive did not contain a tapstate binary."
    mkdir -p "$install_dir"
    staged="$install_dir/.tapstate.$$"
    cp "$tmp/tapstate" "$staged"
    chmod +x "$staged"
    mv "$staged" "$install_dir/tapstate"
    staged=""
}

main() {
    base_url="${TAPSTATE_BASE_URL:-https://github.com/tapstate/tapstate/releases}"
    install_dir="${TAPSTATE_INSTALL_DIR:-$HOME/.tapstate/bin}"

    detect_platform
    # Detect-only mode: print the <os>-<arch> tuple and stop. detect_platform has already refused any
    # unsupported platform above, so this doubles as a zero-side-effect platform gate -- the demo
    # bootstrap calls it before downloading anything, and shares this one copy of the mapping.
    if [ "${1:-}" = --print-platform ]; then
        printf '%s\n' "$platform"
        return
    fi
    resolve_version
    asset="tapstate-${version}-${platform}.tar.gz"
    url="${base_url}/download/v${version}/${asset}"

    tmp="$(mktemp -d)"
    staged=""
    trap 'rm -rf "$tmp" ${staged:+"$staged"}' EXIT INT TERM

    check_minimum_os
    fetch "$url" "$tmp/$asset"
    fetch "${url}.sha256" "$tmp/${asset}.sha256"
    verify_sha256 "$tmp/$asset" "$tmp/${asset}.sha256"
    install_binary

    printf 'tapstate %s installed to %s/tapstate\n' "$version" "$install_dir"
    case ":${PATH}:" in
        *":${install_dir}:"*) : ;;
        *)
            # $PATH is meant to stay literal here — the user pastes this line into their own shell.
            # shellcheck disable=SC2016
            printf 'not on PATH; run it directly, or:  export PATH="%s:$PATH"\n' "$install_dir"
            ;;
    esac
}

main "$@"
