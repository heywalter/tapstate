# Quick start: the online runtime (preview)

> **Preview / POC.** Tapstate's runtime is an early slice: a single-node, in-memory
> engine that executes your `.tap.yml` resources as live pipelines. It is enough to
> run a real end-to-end sync, but it is **not** production-hardened — see
> [Limitations](#limitations) before you rely on it. The offline authoring CLI is
> covered in the [main README](../README.md); this page is the runtime.
>
> **Recommended platform.** Docker with the Compose v2 plugin, plus the system versions
> the release you install names in its notes: a macOS version, and a glibc version on
> Linux. Both are measured from the binaries themselves and follow the machines that
> built them, so an older system may refuse to launch them — check yours with
> `sw_vers -productVersion` or `ldd --version`. You are not blocked from trying: the
> installer names what the build expects and continues, and whether it runs from there
> is yours to own.

What you'll do: bring up a Docker Compose stack — databases, the server, and the
first-admin bootstrap all seeded and started together — then drive a source →
pipeline → target from the CLI so rows move from one datastore to another: snapshot
first, then live change-data-capture (CDC).

The worked example is **MySQL → MongoDB**, but the runtime is connector-agnostic:
any PDK connector works the same way.

## The one-command demo

`quickstart.sh` runs everything on this page for you: it makes itself a
`tapstate-demo` directory, fetches the stack, installs the CLI in place, generates
the demo workspace, brings the stack up, and runs the pipeline — then prints the
target row count and the commands to drive CDC and tear down:

```sh
curl -sSL https://raw.githubusercontent.com/tapstate/tapstate/main/deploy/quickstart/quickstart.sh | sh
```

To read the script before running it, download it into a directory of your own
first — it then works right there:

```sh
mkdir tapstate-demo && cd tapstate-demo
curl -fLO https://raw.githubusercontent.com/tapstate/tapstate/main/deploy/quickstart/quickstart.sh
sh quickstart.sh
```

The rest of this page is that same flow by hand. Run it when you want to see the
online verbs the script drives, or to point the pipeline at your own databases.

> **Preview.** The script installs a released CLI binary and pulls a published
> server image; the version is pinned in the script, so the same script always
> installs the same stack.

## Prerequisites

- **Docker** with the **Compose v2** plugin (`docker compose version`). The stack is
  a single-node local demo: databases, server, and first-admin bootstrap come up
  together, on the loopback interface only.
- **The system version named in the release notes** — a macOS version on a Mac, a glibc
  version on Linux. A recommendation, not a gate; see the note at the top of this page.
- **JDK 21** (`java -version`) — only for the dev overlay below, which builds the
  server image from this checkout. The CLI needs no toolchain at all: it is installed
  as a native binary in step 3, and GraalVM is only for building it from source.

## 1. Get the stack

In this preview the compose file, the server image, and the CLI all come from the
source tree, so clone the repository and move into the quickstart directory:

```sh
git clone https://github.com/tapstate/tapstate.git
cd tapstate/deploy/quickstart
export COMPOSE_FILE=docker-compose.yml:docker-compose.dev.yml
```

Everything below runs from here — this is where the compose file lives, so
`docker compose …` finds it, and the jars and workspace you create sit alongside it.

`docker-compose.yml` on its own names the published server image and never builds,
because that is the file a user downloads into an empty directory where there is no
source tree. The `COMPOSE_FILE` line above adds `docker-compose.dev.yml`, which
builds the server from this checkout instead; every `docker compose …` below then
picks up both without repeating them. Set it once per shell — a new terminal needs
it again.

## 2. Bring up the stack

> **Preview.** With the development override in play, the server image is built
> locally on first run from a repackaged jar, so build that jar once:
> ```sh
> ( cd ../.. && mvn -pl app -am -DskipTests package )    # -> app/target/app-<version>-boot.jar
> ```
> Drop the override once the image is published and this becomes a plain pull.

Start everything:

```sh
docker compose up -d
```

That brings up four services:

- **MongoDB** as a single-member replica set (Tapstate's state store needs one),
  initiated automatically by its healthcheck.
- **MySQL** seeded with a demo `orders` table of five rows, with row-based binary
  logging on so CDC has a binlog to tail.
- the **server** (`--role=all`), published on **`127.0.0.1:8080`** only — an
  unauthenticated first run must not be reachable from other machines.
- a one-shot **bootstrap** that creates the first admin over the server's loopback,
  then exits.

The first admin defaults to **`admin` / `admin`**, which is fine for this
loopback-only demo. To set your own password, copy `.env.example` to `.env` and edit
`TAPSTATE_ADMIN_PASSWORD` before bringing the stack up.

Wait for the server to report healthy (first run also builds the image, so allow up
to a minute):

```sh
docker compose ps          # the "server" row should read "healthy"
```

## 3. Get the CLI

Install it right here in the demo directory — the same installer as a permanent
install, pointed at `.` so that deleting the directory later removes everything:

```sh
curl -sSL https://raw.githubusercontent.com/tapstate/tapstate/main/install/install.sh | TAPSTATE_INSTALL_DIR=. sh
```

(Building from source still works — `mvn -Pnative -pl cli -am -DskipTests package`
in the repository, needs GraalVM for JDK 21 — but nothing in this walkthrough
requires it.)

The CLI drives both the offline authoring loop and the online verbs below. It runs
on the host and talks to the server over HTTP; put it on your `PATH`, or keep it
here as `./tapstate` as above.

## 4. Get the connector jars

This walkthrough needs a MySQL and a MongoDB connector. Prebuilt jars are published
as release assets — download them next to the compose file:

```sh
base=https://github.com/tapstate/tapstate/releases/download/connectors-preview
curl -fL -O "$base/mysql-connector.jar"
curl -fL -O "$base/mongodb-connector.jar"
```

Any PDK connector jar works the same way — these two are published only so this page
runs without building the connector repositories first. They are shaded and carry
their own drivers on an isolated loader; `mysql-connector.jar` bundles Oracle MySQL
Connector/J under GPL-2.0 with the Universal FOSS Exception (see [`NOTICE`](../NOTICE)).

## 5. Author the resources

A workspace is a folder partitioned by resource kind. Create three resources — the
read source, the write target, and the pipeline that connects them:

```sh
mkdir -p work/source work/pipeline
```

The commands below name this workspace explicitly — the verbs take it as an argument
(`tapstate validate work`) and the REPL takes it as a flag (`tapstate -w work`).
Unnamed, the CLI falls back to its default workspace, `tap-work`, and finds nothing.
(`TAPSTATE_WORKDIR=work` in the environment does the same job for both.)

The connector configs address the databases by their **compose service names**
(`mysql`, `mongo`): the connector runs inside the server container, where those
names resolve and loopback is the server itself.

`work/source/db_src.tap.yml` — the read source (the demo MySQL):

```yaml
version: tapstate/v1
kind: source
id: db_src
connector: mysql
config: { host: mysql, port: 3306, database: appdb, username: root, password: secret }
mode: cdc
tables: [ orders ]
```

`work/source/warehouse.tap.yml` — the write target (also `kind: source`):

```yaml
version: tapstate/v1
kind: source
id: warehouse
connector: mongodb
config: { isUri: true, uri: "mongodb://mongo:27017/warehouse?directConnection=true" }
```

`work/pipeline/sync_orders.tap.yml` — the data flow:

```yaml
version: tapstate/v1
kind: pipeline
id: sync_orders
source: db_src
settings: { read_mode: snapshot_and_cdc }
transforms:
  - id: shape_orders
    from: [ orders ]
    type: map
    fields:
      customer_name: $customer
      label: "=after.customer + ' <' + src + '>'"
serve:
  from: shape_orders
  sync:
    - source: warehouse
```

One `map` step reshapes the stream, and it carries every change through — insert, update
and delete. An insert or update is reshaped by the fields below; a delete has no `after`
image, so the map leaves it untouched and the sink removes the row from the target by its
key.

A `map` step lists only the fields it changes — anything unlisted passes through
untouched, so `id` and `amount` arrive at the target as they left the source. Each
entry takes one of four forms:

| Form | Meaning |
|---|---|
| `new: $old` | rename; the source field is consumed |
| `field: false` | drop the field |
| `field: <value>` | set a constant |
| `field: "=<CEL>"` | compute from a CEL row expression |

So `customer_name: $customer` renames the column, and `label` builds a new value from
the row (`after.<field>`) and the change envelope (`src`, plus `op` and `ts`).

> **Numeric columns cannot be used in CEL expressions in this preview.** Row values
> reach CEL as the connector produced them, so an `int` or `decimal` column matches no
> CEL overload — `=string(after.amount)` and `=after.id > 2` both compile offline and
> then fail at runtime with `No matching overload`. Keep expressions to string and
> envelope fields for now; type conversion is not yet usable. (This is why the demo's
> `amount` decimal is only ever passed through, never named in an expression.)

> `serve.from` must name the **last** step you want served — `shape_orders` here. It
> takes a transform `id` or a concrete resource, **not** a regex such as `/.*/`.

Validate offline before going online (no server needed):

```sh
./tapstate validate work       # expects: valid: 3 resources in work
```

## 6. Go online and run

Start the interactive REPL and drive it. The connection is session state, so these
run inside one REPL session:

```console
$ ./tapstate -w work
tapstate(offline:work)> connect http://127.0.0.1:8080
tapstate(127.0.0.1:8080)> login admin
Password:                       # the admin password from step 2 (not echoed)
tapstate(admin@127.0.0.1:8080)> register ../mysql-connector.jar
tapstate(admin@127.0.0.1:8080)> register ../mongodb-connector.jar
tapstate(admin@127.0.0.1:8080)> apply
tapstate(admin@127.0.0.1:8080)> discover-schema db_src
tapstate(admin@127.0.0.1:8080)> start sync_orders
```

- **`register`** uploads a connector jar to the server (content-addressed and
  idempotent; re-registering the same jar is a no-op). Its paths resolve against the
  workspace root — `work/` here — which is why the jars beside it are reached as
  `../mysql-connector.jar`. An absolute path works too, as does naming a directory:
  `register ..` uploads every `*.jar` under it as one batch.
- **`apply`** with no argument applies the whole workspace as one batch. The batch is
  the reference closure — a pipeline and the sources it names must be applied
  together, so apply the workspace, not one file at a time.
- **`discover-schema db_src`** reads the source schema and derives the target model
  and primary key. Run it **before** `start`.
- **`start sync_orders`** submits the pipeline: it reads the current rows (snapshot),
  then tails changes (CDC).

## 7. Observe and verify

```console
tapstate(admin@127.0.0.1:8080)> status sync_orders --watch    # live state; Ctrl-C to stop
tapstate(admin@127.0.0.1:8080)> metrics sync_orders           # recordCount / errorCount / per-table offset
tapstate(admin@127.0.0.1:8080)> logs sync_orders              # node-local operational log tail
```

- The read faces lag the write verbs: they report observed state, which converges to
  what you asked for rather than changing with the command. Immediately after `start`
  the first `status`/`metrics` may report no observation yet, and a `status` right
  after `stop` can still say `running`. Use `--watch`, or retry after a second.
- `metrics` is the signal for progress: `recordCount` climbing, `errorCount` at 0.

Verify the rows landed, straight from the target — `mongosh` runs inside the Mongo
container, so no client is needed on the host:

```sh
docker compose exec mongo mongosh --quiet \
  "mongodb://mongo:27017/warehouse?directConnection=true" \
  --eval "db.orders.countDocuments()"    # should reach 5
```

## 8. Exercise change-data-capture

Change the source in MySQL and watch the target in MongoDB follow. The pipeline
carries insert, update and delete — a delete removes the row from the target by key.
Each change reaches MongoDB in about a second, so each read below waits for it rather
than guessing:

```sh
# insert a row, then wait for it to appear in the target (the sink upserts on the
# discovered key, so the snapshot->CDC overlap never doubles a row)
docker compose exec mysql mysql -uroot -psecret appdb -e "INSERT INTO orders VALUES (6,'frank',60.00);"
until docker compose exec -T mongo mongosh --quiet "mongodb://mongo:27017/warehouse?directConnection=true" --eval 'quit(db.orders.countDocuments({id:6})?0:1)'; do sleep 1; done
docker compose exec mongo mongosh --quiet "mongodb://mongo:27017/warehouse?directConnection=true" --eval 'db.orders.find({id:6}).pretty()'   # customer_name: 'frank', label: 'frank <orders>'

# update it, and watch the mapped label follow the change
docker compose exec mysql mysql -uroot -psecret appdb -e "UPDATE orders SET customer='franky' WHERE id=6;"
until docker compose exec -T mongo mongosh --quiet "mongodb://mongo:27017/warehouse?directConnection=true" --eval 'quit((db.orders.findOne({id:6})||{}).customer_name=="franky"?0:1)'; do sleep 1; done
docker compose exec mongo mongosh --quiet "mongodb://mongo:27017/warehouse?directConnection=true" --eval 'db.orders.find({id:6}).pretty()'

# delete it, and watch it leave the target too
docker compose exec mysql mysql -uroot -psecret appdb -e "DELETE FROM orders WHERE id=6;"
until docker compose exec -T mongo mongosh --quiet "mongodb://mongo:27017/warehouse?directConnection=true" --eval 'quit(db.orders.countDocuments({id:6})?1:0)'; do sleep 1; done
echo "row 6 is gone from MongoDB, too"
```

## 9. Tear down

In the REPL:

```console
tapstate(admin@127.0.0.1:8080)> stop sync_orders
tapstate(admin@127.0.0.1:8080)> exit
```

Then stop the stack and delete its data:

```sh
docker compose down -v     # stops every service and drops the named volumes
```

`down -v` discards the store, so a re-run re-registers the connectors from scratch.
The pulled/built images remain — remove them with `docker image rm <image>` if you
want the machine back exactly as it was. The jars, `work/`, and `.env` you created
here are just files; delete them as usual.

## Alternative: build and run the server from source

Prefer to run the server process directly on the host — to attach a debugger, or to
iterate on server code — rather than in a container? The flow is the same; only how
the server and databases are hosted changes.

1. **Build** the server jar and the CLI:

   ```sh
   mvn -DskipTests install          # -> app/target/app-<version>-boot.jar (the runtime server)
   mvn -Pnative -pl cli -am -DskipTests package   # -> cli/target/tapstate
   ```

2. **Databases on the host.** The compose stack publishes no host ports for MongoDB
   and MySQL, so run your own with ports exposed (or point at databases you already
   have). MongoDB must be a single-node replica set advertising `127.0.0.1`; MySQL
   needs row-based binary logging for CDC:

   ```sh
   docker run -d --name tapstate-mongo -p 27017:27017 mongo:7 --replSet rs0
   until docker exec tapstate-mongo mongosh --quiet --eval 'db.runCommand({ping:1})' >/dev/null 2>&1; do sleep 2; done
   docker exec tapstate-mongo mongosh --quiet --eval \
     "rs.initiate({_id:'rs0',members:[{_id:0,host:'127.0.0.1:27017'}]})"

   docker run -d --name tapstate-mysql -e MYSQL_ROOT_PASSWORD=secret -e MYSQL_DATABASE=appdb \
     -p 3306:3306 mysql:8.0 \
     --server-id=1 --log-bin=mysql-bin --binlog-format=ROW --gtid-mode=ON --enforce-gtid-consistency=ON
   until docker exec tapstate-mysql mysqladmin ping -uroot -psecret --silent 2>/dev/null; do sleep 2; done
   docker exec -i tapstate-mysql mysql -uroot -psecret appdb < deploy/quickstart/mysql-init/01-orders.sql
   ```

   The last line seeds MySQL with the **same** `orders` data the compose stack uses —
   one sample, not two that drift.

3. **Start the server** on the host with JDK 21, pointing it at your Mongo:

   ```sh
   mkdir -p ./plugins       # a writable cache the server unpacks registered connectors into
   java -jar app/target/app-<version>-boot.jar --role=all \
     --tapstate.store.mongo.uri="mongodb://127.0.0.1:27017/tapstate?replicaSet=rs0" \
     --tapstate.connectors.plugins-dir=./plugins
   ```

   It listens on port **8080**. A Hazelcast `--add-opens` warning at startup is harmless.

4. **First admin.** There is no bootstrap sidecar here, so create the first user with
   a one-time, localhost-only `curl` (a `204 No Content` means success):

   ```sh
   curl -X POST http://localhost:8080/auth/bootstrap \
     -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin123"}'
   ```

5. **Resources.** Use the same three resources as [step 5](#5-author-the-resources),
   with one change: the server now runs on the host, not in the compose network, so
   the connectors address the databases by their host ports instead of the compose
   service names — `config: { host: 127.0.0.1, port: 3306, … }` in `db_src`, and
   `uri: "mongodb://127.0.0.1:27017/warehouse"` in `warehouse`.

6. **Online verbs, observe, CDC** are identical to steps 6–8, except you reach the
   databases with your own client (`docker exec tapstate-mysql …` /
   `docker exec tapstate-mongo mongosh "mongodb://127.0.0.1:27017/warehouse" …`)
   rather than `docker compose exec`.

Tear down with `docker rm -f tapstate-mysql tapstate-mongo` and `Ctrl-C` in the
server's terminal.

## Limitations

This runtime is a preview. Known constraints in this slice:

- **Single node, in-memory.** No multi-node HA. A server restart does **not** resume
  from a persisted offset — it replays from the source (idempotent upsert absorbs the
  overlap). Durable resume / exactly-once are not in this preview.
- **Preview builds.** Until the first release, the server image is assembled locally
  and the CLI is built from source; a published image and a CLI installer remove
  those steps.
- **`logs` is thin.** The per-pipeline `logs` face is a node-local operational tail
  and is often sparse; full runtime detail is in the server process log.
- **No CLI bootstrap verb.** The compose stack creates the first admin for you; on
  the from-source path it is the `curl` above.
- **One-shot online verbs don't persist a session.** The online verbs are driven from
  the REPL, where the connection is session state.
