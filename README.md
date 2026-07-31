# Tapstate - Coming soon, stay tuned.

**Capture. Transform. Serve. One binary.**

Tapstate is an open-source operational state engine that turns production database changes into fresh, queryable state for applications, APIs, and AI agents.

Instead of operating a stack of CDC tooling, Kafka, stream processing, and a separate serving cache, Tapstate gives teams one deployable data path:

```text
production database -> log-based CDC -> in-flight transform -> queryable live state
```

Tapstate is built for platform and data infrastructure teams that need current business state without turning freshness into an integration project.

> From the team behind TapData, hardened through years of production CDC and real-time data movement work.

## Why Tapstate

Modern systems increasingly depend on fresh operational data:

- Applications need current account, order, inventory, entitlement, and customer state.
- AI agents need up-to-date business context, not yesterday's warehouse snapshot.
- Data teams need lower-latency pipelines without adding more infrastructure to operate.
- Platform teams need correctness, recovery, and observability across the whole path.

The common answer is a hand-assembled stack:

```text
Debezium + Kafka / Redpanda + Flink / custom jobs + Redis / MongoDB / Elasticsearch
```

That stack can work, but it creates multiple failure surfaces, upgrade cycles, schemas, offsets, dashboards, and on-call paths.

Tapstate collapses the live operational path into one engine.

## What Tapstate Does

### Capture

Tapstate reads changes from production databases using log-based CDC.

- Initial load plus continuous sync
- Ordered change streams
- Checkpointed recovery
- Schema evolution handling
- Minimal source impact

### Transform

Tapstate reshapes changes in flight before they are served.

- Filter and route change streams
- Enrich and denormalize records
- Join related operational data
- Materialize application-ready views
- Avoid running a separate stream-processing cluster

### Serve

Tapstate serves continuously fresh operational state through standard query interfaces.

- Queryable materialized views
- Current state for applications and APIs
- Operational context for AI agents
- Standard drivers, no custom SDK required

## Example

Run the live runtime locally as a Docker Compose stack — databases, server, and
first-admin bootstrap brought up together — driving a real MySQL → MongoDB sync
(snapshot, then live CDC). One command does the whole flow, in a `tapstate-demo`
directory it makes for itself:

```sh
curl -sSL https://raw.githubusercontent.com/tapstate/tapstate/main/deploy/quickstart/quickstart.sh | sh
```

Prefer to read it first? Download, read, then run — same script, and it works in
the directory you saved it in:

```sh
mkdir tapstate-demo && cd tapstate-demo
curl -fLO https://raw.githubusercontent.com/tapstate/tapstate/main/deploy/quickstart/quickstart.sh
sh quickstart.sh
```

Everything it installs stays inside that one directory; tearing down is
`docker compose down -v` in it, and `rm -rf` of the directory removes the rest.
See [docs/quickstart-online.md](docs/quickstart-online.md) for the full walkthrough
and the manual `docker compose` steps behind it.

> **Preview.** The runtime is an early slice: single-node, in-memory, and a restart
> replays from the source rather than resuming a persisted offset — not for
> production. `quickstart.sh` downloads a released CLI and server image. See
> [Limitations](docs/quickstart-online.md#limitations).
>
> **Recommended platform.** Docker with Compose v2, plus the system versions each
> release names in its notes: a macOS version, and a glibc version on Linux. Both are
> measured from the binaries themselves and follow the machines that built them, so an
> older system may refuse to launch them — `sw_vers -productVersion` and `ldd --version`
> say what a machine has. Nothing stops you from installing anyway: the installer names
> what the build expects and carries on, and how it goes from there is yours.

To install just the CLI — the offline authoring loop, one native binary, no Docker,
no JDK:

```bash
curl -sSL https://raw.githubusercontent.com/tapstate/tapstate/main/install/install.sh | sh
```

It installs a preview build to `~/.tapstate/bin`, verifies the download against its
published checksum, and never asks for sudo or edits your shell configuration. Prefer
to read before you run — or to skip the pipe entirely? Both are supported:

```bash
# Audit the script first, then run the copy you read:
curl -sSL https://raw.githubusercontent.com/tapstate/tapstate/main/install/install.sh -o install.sh && less install.sh && sh install.sh

# Or skip the installer: download a release asset and check it yourself.
# Assets are tapstate-<version>-<os>-<arch>.tar.gz, each next to its .sha256
# (and all of them listed in the release's checksums.txt):
v=0.1.0 p=darwin-arm64   # your version, and your platform: darwin|linux, arm64|x64
curl -sSLO "https://github.com/tapstate/tapstate/releases/download/v$v/tapstate-$v-$p.tar.gz"
curl -sSLO "https://github.com/tapstate/tapstate/releases/download/v$v/tapstate-$v-$p.tar.gz.sha256"
shasum -a 256 -c "tapstate-$v-$p.tar.gz.sha256"   # sha256sum -c on Linux
tar -xzf "tapstate-$v-$p.tar.gz"
```

To uninstall: `rm -rf ~/.tapstate`, and drop the `PATH` line from your shell
configuration if you added one.

```yaml
# tapstate.yaml
source:
  type: postgres
  host: localhost
  database: app

pipeline:
  - capture: public.orders
  - transform:
      view: order_state
      key: order_id

serve:
  protocol: mongodb
  port: 27017
```

```javascript
// Query fresh operational state with a standard driver.
const order = await db.collection("order_state").findOne({ order_id: "ord_123" });
```

## Use Cases

- Real-time customer and account 360
- Operational data APIs
- Core system offloading
- Live master data
- Real-time analytics feeds
- Fresh context for AI agents
- Inventory, order, entitlement, and risk state

## Tapstate vs. The Assembly Project

| Need | Traditional stack | Tapstate |
| --- | --- | --- |
| Capture database changes | Debezium or custom CDC | Built-in log-based CDC |
| Move events | Kafka / Redpanda | Integrated data path |
| Transform streams | Flink / jobs / glue code | In-flight transforms |
| Serve current state | Cache / search / document DB | Queryable live state |
| Operate reliability | Multiple dashboards and failure modes | One operational surface |

Kafka moves events. Warehouses analyze history. Tapstate turns database truth into live operational state.

## Project Status

Tapstate is early and actively evolving.

This repository is intended for developers, platform engineers, and data infrastructure teams who want to try the engine, follow development, and help shape the open-source project.

Production users should review the architecture, deployment model, and operational guarantees before adopting Tapstate in critical paths.

## Building and running from source

The vision above is where Tapstate is going; this section is the real, working
path today. You describe integration resources — sources, pipelines, transforms,
views, and publish surfaces — as small declarative `.tap.yml` documents, and
Tapstate moves and reshapes the data. The repository ships an **offline authoring
CLI** (a single native binary that creates, validates, and explores `.tap.yml`
resources with no server, database, or network) plus an early **preview runtime**
that executes those resources as live pipelines.

### Requirements

- **To run the CLI:** no runtime to install — `tapstate` is a native binary (starts in
  ~30 ms). Each build does carry a recommended system version — a macOS version, or a
  glibc version on Linux — that tracks the machine it was built on and can move between
  releases, so every release names its own in its notes. Installing on an older system is
  allowed: the installer says what the build expects and carries on; whether it launches
  there is then up to you.
- **To build from source:** **Oracle GraalVM for JDK 21** (includes `native-image`)
  and **Maven 3.6+**. A plain JDK 21 is enough to build and run the test suite;
  GraalVM is only needed for the native image.

### Build

From the repository root:

```sh
# Full build + unit tests (needs a JDK 21 toolchain)
mvn verify

# Native CLI binary (needs GraalVM for JDK 21 on JAVA_HOME)
mvn -Pnative -pl cli -am -DskipTests package
```

The native binary lands at **`cli/target/tapstate`** (the connector catalog,
grammar schema, and message text are embedded). Put it on your `PATH`:

```sh
export PATH="$PWD/cli/target:$PATH"      # this shell
# or
ln -s "$PWD/cli/target/tapstate" ~/bin/tapstate
```

Verify:

```console
$ tapstate --version
tapstate 0.1.0
```

### Quick start (offline CLI)

The offline workspace is an ordinary folder, partitioned by resource kind. Create
a source, wire it into a pipeline, and validate — all without a server:

```console
$ tapstate new -y --kind source --connector mysql --id orders_src -m cdc
created source/orders_src.tap.yml

$ tapstate new -y --kind source --connector postgres --id warehouse -m cdc
created source/warehouse.tap.yml

$ tapstate new -y --kind pipeline --id orders_sync --source orders_src --sync-to warehouse
created pipeline/orders_sync.tap.yml

$ tapstate validate
valid: 3 resources
```

Each `new` writes a deterministic, canonical `.tap.yml`:

```yaml
version: tapstate/v1
kind: source
id: orders_src
connector: mysql
mode: cdc
```

`validate` runs three offline checks — **structure** (schema: unknown fields /
types / enums), **reference closure** (referenced ids exist), and **capability
matrix** (mode × connector legality) — and every diagnostic carries a code, a
location, and a fix suggestion. Browse and document your workspace with `ls`,
`desc <id>`, and `explain <field>`; run `tapstate` with no arguments for an
offline REPL with Tab completion. All five offline verbs (`new` / `validate` /
`ls` / `desc` / `explain`) accept `-o json|yaml` for machine-readable output.

### Editor integration

The grammar ships as a standard JSON Schema (draft 2020-12) in the tree at
`core/core-schema/src/main/resources/schema/tapstate-v1.schema.json`. Associate
`*.tap.yml` with it for live validation, completion, and hover docs in any editor
backed by [yaml-language-server](https://github.com/redhat-developer/yaml-language-server):

```jsonc
// VS Code settings.json
{
  "yaml.schemas": {
    "/abs/path/to/tapstate/core/core-schema/src/main/resources/schema/tapstate-v1.schema.json": "*.tap.yml"
  }
}
```

### Preview runtime

Beyond the offline verbs, `connect` / `login` / `register` / `apply` /
`discover-schema` / `start` / `status` / `metrics` / `logs` (and friends) drive a
running Tapstate server — see [docs/quickstart-online.md](docs/quickstart-online.md)
for the end-to-end flow (build → start a server → run a real sync). That runtime is
an early **preview** (single-node, in-memory); offline, without a connection, those
verbs exit with code `3`.

## Roadmap

- [x] Public quickstart
- [ ] Supported source and target matrix
- [ ] Architecture guide
- [ ] Local development environment
- [ ] Benchmarks
- [ ] Deployment examples
- [ ] Contributor guide

## Documentation

- Quickstart: coming soon
- Architecture: coming soon
- Connectors: coming soon
- Deployment: coming soon
- FAQ: coming soon

## Community

- Website: https://tapstate.com
- GitHub: https://github.com/tapstate/tapstate
- Discussions: coming soon
- Issues: https://github.com/tapstate/tapstate/issues

## Relationship to TapData

Tapstate is an open-source operational state engine from the team behind TapData.

TapData is a mature real-time data integration and operational data platform used in enterprise environments. Tapstate focuses on a simpler, developer-first path: capture, transform, and serve live operational state in one deployable.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Tapstate is licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
