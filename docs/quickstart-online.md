# Quick start: the online runtime (preview)

> **Preview / POC.** Tapstate's runtime is an early slice: a single-node, in-memory
> engine that executes your `.tap.yml` resources as live pipelines. It is enough to
> run a real end-to-end sync, but it is **not** production-hardened — see
> [Limitations](#limitations) before you rely on it. The offline authoring CLI is
> covered in the [main README](../README.md); this page is the runtime.

What you'll do: bring up two databases, build the server, start it, author a
source → pipeline → target, and drive it online from the CLI so rows move from one
datastore to another — snapshot first, then live change-data-capture (CDC).

The worked example is **MySQL → MongoDB**, but the runtime is connector-agnostic:
any PDK connector works the same way.

## Prerequisites

- **JDK 21** to run the server and CLI. The code targets Java 21; launching it on an
  older JVM fails with `UnsupportedClassVersionError`. Use a GraalVM or plain JDK 21
  explicitly (e.g. `JAVA_HOME=/path/to/jdk-21`).
- **Docker** — to bring up throwaway MySQL and MongoDB for this walkthrough (next
  section). Already have a MySQL (with binary logging on, for CDC) and a MongoDB
  replica set? Use those and substitute their host/port/credentials below.
- **Connector plugin jars** for the datastores you use. This walkthrough needs a MySQL
  and a MongoDB connector; prebuilt ones are published as release assets, so download
  them into `connectors/` at the repository root:

  ```sh
  mkdir -p connectors && cd connectors
  base=https://github.com/tapstate/tapstate/releases/download/connectors-preview
  curl -fL -O "$base/mysql-connector.jar"
  curl -fL -O "$base/mongodb-connector.jar"
  cd ..
  ```

  Any PDK connector jar works the same way — these two are published only so this
  page runs without building the connector repositories first. They are shaded and
  carry their own drivers on an isolated loader; `mysql-connector.jar` bundles Oracle
  MySQL Connector/J under GPL-2.0 with the Universal FOSS Exception (see
  [`NOTICE`](../NOTICE)).

## Set up development databases (Docker)

Throwaway MySQL and MongoDB for this walkthrough. **Skip this** if you brought your
own — just substitute your values in the resources further down.

Start MySQL with binary logging on (CDC needs `binlog_format=ROW`):

```sh
docker run -d --name tapstate-mysql \
  -e MYSQL_ROOT_PASSWORD=secret -e MYSQL_DATABASE=appdb \
  -p 3306:3306 mysql:8.0 \
  --server-id=1 --log-bin=mysql-bin --binlog-format=ROW \
  --gtid-mode=ON --enforce-gtid-consistency=ON \
  --default-authentication-plugin=mysql_native_password
```

Start MongoDB as a single-node replica set (Tapstate's state store needs a replica
set), then initiate it so it advertises `127.0.0.1`:

```sh
docker run -d --name tapstate-mongo -p 27017:27017 mongo:7 --replSet rs0

# wait for it to accept connections, then initiate the set:
until docker exec tapstate-mongo mongosh --quiet --eval 'db.runCommand({ping:1})' >/dev/null 2>&1; do sleep 2; done
docker exec tapstate-mongo mongosh --quiet --eval \
  "rs.initiate({_id:'rs0',members:[{_id:0,host:'127.0.0.1:27017'}]})"
```

Load test data into MySQL — a table with a primary key and a few rows:

```sh
# wait for MySQL's first boot to finish, then seed:
until docker exec tapstate-mysql mysqladmin ping -uroot -psecret --silent 2>/dev/null; do sleep 2; done

docker exec -i tapstate-mysql mysql -uroot -psecret appdb <<'SQL'
CREATE TABLE IF NOT EXISTS orders (
  id       INT PRIMARY KEY,
  customer VARCHAR(64),
  amount   DECIMAL(10,2)
);
INSERT INTO orders (id, customer, amount) VALUES
  (1,'alice',10.00), (2,'bob',20.00), (3,'carol',30.00),
  (4,'dave',40.00),  (5,'erin',50.00);
SQL
```

That is five `orders` rows keyed by `id` — the snapshot Tapstate will copy across.

## 1. Build the server and CLI

From the repository root (a JDK 21 toolchain is required — see the README):

```sh
mvn -DskipTests install          # builds every module; server fat-jar included
# → app/target/app-<version>-boot.jar   (the runtime server)
```

Build the native CLI as the README describes (`mvn -Pnative -pl cli -am -DskipTests
package` → `cli/target/tapstate`), and put it on your `PATH`. The CLI drives both the
offline authoring loop and the online verbs used below.

> Full unit tests: `mvn verify` (slower). Container-backed integration tests need
> Docker.

## 2. Start the server

```sh
mkdir -p ./plugins       # a writable cache the server unpacks registered connectors into

java -jar app/target/app-<version>-boot.jar --role=all \
  --tapstate.store.mongo.uri="mongodb://127.0.0.1:27017/tapstate?replicaSet=rs0" \
  --tapstate.connectors.plugins-dir=./plugins
```

- Run this with **JDK 21** (`java -version`).
- `replicaSet=` must match your MongoDB's replica-set name (`rs0` from the setup above).
- The server listens on port **8080**. Wait for `Started ... in N seconds` /
  `Tomcat started on port 8080`, then leave it running and open a second terminal.
- A Hazelcast `--add-opens` warning at startup is harmless.

## 3. Create the first admin

The server allows a one-time, localhost-only bootstrap of the first user. There is
**no CLI verb for this yet** (a known preview gap), so use `curl`:

```sh
curl -X POST http://localhost:8080/auth/bootstrap \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}'
```

A `204 No Content` means success.

## 4. Author the resources

A workspace is a folder partitioned by resource kind. Create three resources — the
read source, the write target, and the pipeline that connects them:

```sh
mkdir -p work/source work/pipeline
```

Stay at the repository root; the commands below name this workspace explicitly — the
verbs take it as an argument (`tapstate validate work`) and the REPL takes it as a
flag (`tapstate -w work`). Unnamed, the CLI falls back to its default workspace,
`tap-work`, and finds nothing. (`TAPSTATE_WORKDIR=work` in the environment does the
same job for both.)

`work/source/db_src.tap.yml` — the read source (the dev MySQL):

```yaml
version: tapstate/v1
kind: source
id: db_src
connector: mysql
config: { host: 127.0.0.1, port: 3306, database: appdb, username: root, password: secret }
mode: cdc
tables: [ orders ]
```

`work/source/warehouse.tap.yml` — the write target (also `kind: source`):

```yaml
version: tapstate/v1
kind: source
id: warehouse
connector: mongodb
config: { isUri: true, uri: "mongodb://127.0.0.1:27017/warehouse" }
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
> envelope fields for now; type conversion is not yet usable.

> `serve.from` must name the **last** step you want served — `shape_orders` here. It
> takes a transform `id` or a concrete resource, **not** a regex such as `/.*/`.

Validate offline before going online (no server needed):

```sh
tapstate validate work       # expects: valid: 3 resources in work
```

## 5. Go online and run

Start the interactive REPL and drive it. The connection is session state, so these
run inside one REPL session:

```console
$ tapstate -w work
tapstate(offline:work)> connect http://localhost:8080
tapstate(localhost:8080)> login admin
Password:                       # type the password from step 3 (not echoed)
tapstate(admin@localhost:8080)> register ../connectors/mysql-connector.jar
tapstate(admin@localhost:8080)> register ../connectors/mongodb-connector.jar
tapstate(admin@localhost:8080)> apply
tapstate(admin@localhost:8080)> discover-schema db_src
tapstate(admin@localhost:8080)> start sync_orders
```

- **`register`** uploads a connector jar to the server (content-addressed and
  idempotent; re-registering the same jar is a no-op). Its paths resolve against the
  workspace root — `work/` here — which is why the jars in `connectors/` next to it
  are reached as `../connectors/`. An absolute path works too, as does naming a
  directory: `register ../connectors` uploads every `*.jar` under it as one batch.
- **`apply`** with no argument applies the whole workspace as one batch. The batch is
  the reference closure — a pipeline and the sources it names must be applied
  together, so apply the workspace, not one file at a time.
- **`discover-schema db_src`** reads the source schema and derives the target model
  and primary key. Run it **before** `start`.
- **`start sync_orders`** submits the pipeline: it reads the current rows (snapshot),
  then tails changes (CDC).

## 6. Observe and verify

```console
tapstate(admin@localhost:8080)> status sync_orders --watch    # live state; Ctrl-C to stop
tapstate(admin@localhost:8080)> metrics sync_orders           # recordCount / errorCount / per-table offset
tapstate(admin@localhost:8080)> logs sync_orders              # node-local operational log tail
```

- The read faces lag the write verbs: they report observed state, which converges to
  what you asked for rather than changing with the command. Immediately after `start`
  the first `status`/`metrics` may report no observation yet, and a `status` right
  after `stop` can still say `running`. Use `--watch`, or retry after a second.
- `metrics` is the signal for progress: `recordCount` climbing, `errorCount` at 0.

Verify the rows landed, straight from the target:

```sh
docker exec tapstate-mongo mongosh --quiet "mongodb://127.0.0.1:27017/warehouse" \
  --eval "print(db.orders.countDocuments())"    # should reach 5
```

Now exercise CDC: change the source and watch the target follow. The pipeline carries
insert, update and delete — a delete removes the row from the target by key. Each change
reaches MongoDB in about a second, so give the read a moment (or re-run it):

```sh
# insert -> the target grows to 6 (the sink upserts on the discovered key, so the
# snapshot->CDC overlap never doubles a row)
docker exec tapstate-mysql mysql -uroot -psecret appdb \
  -e "INSERT INTO orders (id, customer, amount) VALUES (6,'frank',60.00);"
docker exec tapstate-mongo mongosh --quiet "mongodb://127.0.0.1:27017/warehouse" \
  --eval "printjson(db.orders.findOne({id:6}))"   # customer_name: 'frank', label: 'frank <orders>'

# update -> the mapped label follows the change
docker exec tapstate-mysql mysql -uroot -psecret appdb \
  -e "UPDATE orders SET customer='franky' WHERE id=6;"

# delete -> the row leaves the target too
docker exec tapstate-mysql mysql -uroot -psecret appdb \
  -e "DELETE FROM orders WHERE id=6;"
docker exec tapstate-mongo mongosh --quiet "mongodb://127.0.0.1:27017/warehouse" \
  --eval "print(db.orders.countDocuments())"      # back to 5
```

## 7. Tear down

```console
tapstate(admin@localhost:8080)> stop sync_orders
tapstate(admin@localhost:8080)> exit
```

Stop the server with `Ctrl-C` in its terminal, and remove the dev databases:

```sh
docker rm -f tapstate-mysql tapstate-mongo
```

## Limitations

This runtime is a preview. Known constraints in this slice:

- **Single node, in-memory.** No multi-node HA. A server restart does **not** resume
  from a persisted offset — it replays from the source (idempotent upsert absorbs the
  overlap). Durable resume / exactly-once are not in this preview.
- **`logs` is thin.** The per-pipeline `logs` face is a node-local operational tail
  and is often sparse; full runtime detail is in the server process log.
- **No CLI bootstrap verb.** The first admin is created with the `curl` in step 3.
- **One-shot online verbs don't persist a session.** The online verbs are driven from
  the REPL, where the connection is session state.
