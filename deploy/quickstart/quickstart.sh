#!/bin/sh
#
# tapstate quickstart — brings up the local demo from an empty directory: it downloads the compose stack,
# the platform's CLI, and the demo connector jars, generates a demo workspace and a .env with a random
# admin password, then starts the stack and runs a real MySQL -> MongoDB pipeline. Nothing is built.
#
# Usage (download-then-run, never piped -- you already have a directory here, so a saved file can be
# re-read, re-run, and its failures inspected):
#   mkdir tapstate-demo && cd tapstate-demo
#   curl -fLO <base>/quickstart.sh
#   sh quickstart.sh
#
# It never uses sudo, never edits a shell rc, and installs the CLI in place (./tapstate, not on PATH), so
# `rm -rf` of this directory removes everything it added. An unsupported platform fails before anything is
# fetched, leaving this directory as you found it.
#
# Environment seams:
#   TAPSTATE_QUICKSTART_BASE_URL  where install.sh, the compose file, and the seed SQL are fetched from.
#   TAPSTATE_BASE_URL             CLI release base, passed through to install.sh.
#   TAPSTATE_VERSION              pin the CLI version (default: the latest release).
#   TAPSTATE_CONNECTORS_URL       base URL for the demo connector jars.
#   TAPSTATE_QUICKSTART_PREPARE_ONLY  stop after preparing the directory, before Docker (used by tests).
#
# POSIX sh, no bashisms. All work is inside main().
set -eu

die() {
    printf 'quickstart: %s\n' "$1" >&2
    exit 1
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

# Generate the demo workspace: a source, a target, and the pipeline joining them, ready to apply. The
# addresses are compose service names because the connector runs inside the server container, where
# loopback is the server itself. The heredocs are quoted so $customer stays the literal DSL rename token
# it is, not a shell variable. This mirrors the online walkthrough's sample on purpose -- one sample, not
# two that drift -- including its filter, which drops delete envelopes so the map never dereferences a
# null `after`. The decimal `amount` column is only ever passed through, never named in a CEL: numeric
# columns have no CEL overload in this preview.
generate_workspace() {
    mkdir -p work/source work/pipeline
    cat > work/source/db_src.tap.yml <<'YAML'
version: tapstate/v1
kind: source
id: db_src
connector: mysql
config: { host: mysql, port: 3306, database: appdb, username: root, password: secret }
mode: cdc
tables: [ orders ]
YAML
    cat > work/source/warehouse.tap.yml <<'YAML'
version: tapstate/v1
kind: source
id: warehouse
connector: mongodb
config: { isUri: true, uri: "mongodb://mongo:27017/warehouse?directConnection=true" }
YAML
    cat > work/pipeline/sync_orders.tap.yml <<'YAML'
version: tapstate/v1
kind: pipeline
id: sync_orders
source: db_src
settings: { read_mode: snapshot_and_cdc }
transforms:
  - { id: keep_writes, from: [ orders ], type: filter, expr: "op != 'd'" }
  - id: shape_orders
    from: [ keep_writes ]
    type: map
    fields:
      customer_name: $customer
      label: "=after.customer + ' <' + src + '>'"
serve:
  from: shape_orders
  sync:
    - source: warehouse
YAML
}

# Closing instructions: how to watch the pipeline, exercise CDC, and remove everything. The teardown is
# printed because "back to a clean machine" is only honest if the images are called out too. A later pass
# refines the CDC section (delay handling, and keeping its operations consistent with the pipeline's
# delete filter); the demo pipeline drops deletes, so only an insert is shown here.
print_next_steps() {
    demo_dir="$(basename "$PWD")"
    cat <<EOF
quickstart: pipeline started. The stack is running.

Watch it (from this directory):
  ./tapstate -w work        then: connect http://127.0.0.1:8080 ; login admin ; status sync_orders --watch

See change-data-capture (run in this directory):
  docker compose exec mysql mysql -uroot -psecret appdb -e "INSERT INTO orders VALUES (6,'frank',60.00);"
  docker compose exec mongo mongosh --quiet "mongodb://mongo:27017/warehouse?directConnection=true" --eval "db.orders.countDocuments()"

Tear down (run in this directory):
  docker compose down -v     stop the stack and delete its data (a re-run re-registers the connectors)
  cd .. && rm -rf $demo_dir  remove this directory (CLI, jars, workspace, .env)
The pulled images remain; remove them with:  docker image rm <image>
EOF
}

main() {
    # The release process pins this base to the release tag and its final hosting; it tracks the repo
    # layout for now.
    qbase="${TAPSTATE_QUICKSTART_BASE_URL:-https://raw.githubusercontent.com/tapstate/tapstate/main}"

    work="$(mktemp -d)"
    trap 'rm -rf "$work"' EXIT INT TERM

    # Platform gate: fetch install.sh into the throwaway work area and reuse its detection. This refuses
    # an unsupported platform (Windows shell, musl, unknown OS/arch) before anything is written into the
    # demo directory, and shares one copy of the uname mapping rather than duplicating it here.
    fetch "${qbase}/install/install.sh" "$work/install.sh"
    if ! platform="$(sh "$work/install.sh" --print-platform)"; then
        exit 1   # install.sh already said why (musl / Windows / unknown), pointing to WSL or source
    fi

    # Fetch the stack into this directory, each asset only if absent, so a re-run neither re-downloads a
    # verified asset nor overwrites an edit the user made to it. The seed dir is created empty on purpose:
    # a registered jar's bytes live in the store, and the demo registers over the CLI upload path, so the
    # seed stays the documented empty convenience rather than the route registration depends on.
    mkdir -p mysql-init connectors
    [ -f ./docker-compose.yml ]           || fetch "${qbase}/deploy/quickstart/docker-compose.yml" ./docker-compose.yml
    [ -f ./mysql-init/01-orders.sql ]     || fetch "${qbase}/deploy/quickstart/mysql-init/01-orders.sql" ./mysql-init/01-orders.sql

    # Install the CLI in place as ./tapstate, reusing install.sh wholesale (download, checksum, atomic
    # place). TAPSTATE_INSTALL_DIR here is the seam that keeps it out of PATH: `rm -rf` of this directory
    # removes it. install.sh's own stdout (a PATH hint that does not apply in place) is dropped; its
    # errors still surface and abort under set -e.
    if [ ! -x ./tapstate ]; then
        TAPSTATE_INSTALL_DIR="$PWD" sh "$work/install.sh" >/dev/null
        # A binary fetched by a browser carries macOS's quarantine attribute, which blocks it from running
        # until cleared; install.sh's atomic move preserves it. Strip it -- only on macOS, only if xattr
        # is present, and tolerating the case where the attribute was never set.
        case "$platform" in
            darwin-*) command -v xattr >/dev/null 2>&1 && xattr -d com.apple.quarantine ./tapstate 2>/dev/null || true ;;
        esac
    fi

    # The demo connector jars. Any PDK jar registers the same way; these two are fetched so the demo runs
    # without the user choosing. They sit outside connectors/ so the seed dir stays empty.
    cbase="${TAPSTATE_CONNECTORS_URL:-${TAPSTATE_BASE_URL:-https://github.com/tapstate/tapstate/releases}/download/connectors-preview}"
    [ -f ./mysql-connector.jar ]   || fetch "${cbase}/mysql-connector.jar" ./mysql-connector.jar
    [ -f ./mongodb-connector.jar ] || fetch "${cbase}/mongodb-connector.jar" ./mongodb-connector.jar

    # A random admin password replaces the shipped admin/admin default so a stack left running is not
    # trivially reachable. It is written only to .env (readable by this user alone) and announced once
    # here -- never passed as a CLI argument, so it stays out of the process table and shell history. A
    # re-run keeps the existing .env: regenerating it would lock the user out of the admin already
    # bootstrapped against the old password.
    if [ ! -f .env ]; then
        admin_pw="$(LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 24)"
        printf 'TAPSTATE_ADMIN_USER=admin\nTAPSTATE_ADMIN_PASSWORD=%s\n' "$admin_pw" > .env
        chmod 600 .env
        printf 'quickstart: generated a random admin password, saved to .env: %s\n' "$admin_pw"
    fi

    # Generate the demo workspace, unless one is already here: a re-run must not clobber edits the user
    # made to their resources.
    if [ ! -d work ]; then
        generate_workspace
    fi

    if [ -n "${TAPSTATE_QUICKSTART_PREPARE_ONLY:-}" ]; then
        return
    fi

    # Bring up the stack. The compose file pins the published image, so this pulls rather than builds.
    docker compose up -d

    # Wait until the server container reports healthy -- its image carries the /healthz healthcheck -- so
    # the online verbs are not driven before the server can answer. By then the bootstrap sidecar has
    # created the admin from .env.
    printf 'quickstart: waiting for the stack to become healthy'
    i=0
    while [ "$i" -lt 90 ]; do
        if docker compose ps --format json server 2>/dev/null | grep -q '"Health":"healthy"'; then
            break
        fi
        i=$((i + 1)); printf '.'; sleep 2
    done
    printf '\n'
    docker compose ps --format json server 2>/dev/null | grep -q '"Health":"healthy"' \
        || die "the server did not become healthy in time; inspect it with: docker compose logs server"

    # Drive the online verbs through the REPL, feeding the password on stdin (the login prompt reads the
    # next line) so it is never a process argument or a shell-history entry. Workspace paths resolve
    # against work/, so the jars beside it are ../<jar>.
    admin_pw="$(sed -n 's/^TAPSTATE_ADMIN_PASSWORD=//p' .env)"
    printf 'connect http://127.0.0.1:8080\nlogin admin\n%s\nregister ../mysql-connector.jar\nregister ../mongodb-connector.jar\napply\ndiscover-schema db_src\nstart sync_orders\nexit\n' "$admin_pw" \
        | ./tapstate -w work

    print_next_steps
}

main "$@"
