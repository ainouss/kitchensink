# kitchensink-sync

The **data migration**: it copies the legacy's PostgreSQL table into MongoDB, keeps it up to date, proves both are
equal, and after the switch copies the changes back. It lives until the legacy is retired.

The whole story is in [the migration](../docs/migration.md).

## What it does

| Part | Package | What |
|---|---|---|
| **Copy** (legacy → MongoDB) | `forward` | Debezium reads a consistent snapshot of the `member` table, then every change from PostgreSQL's log. Each batch is written to MongoDB in commit order, and only if it is newer than what is stored |
| **Reconciliation** | `reconcile` | Compares both databases: the counts, then a hash of every record. A difference is checked again after a few seconds, so changes still in flight are not reported |
| **Copy back** (MongoDB → legacy) | `reverse` | After the switch, MongoDB's change streams are written back into PostgreSQL, so a rollback loses nothing |
| **Mapping** | `member` | One row → one document, values unchanged, with the rules an old row breaks (`quality`) and the source's log position (`sync`) |
| **Status** | `status` | Lag, counters, health and metrics |

One direction runs at a time. The last direction started is saved, so a restart continues in the same direction.
Before any has started, `SYNC_FORWARD_ENABLED` decides: `ks start` sets it to `false`, so the copy waits for `ks sync`.

## Its API

Used by `ks cutover` and `ks rollback`. Keep it internal: it is not behind the facade.

| Call | What |
|---|---|
| `GET /sync/status` | Both directions: state, lag, counters, the last reconciliation |
| `POST /sync/reconcile` | Runs a reconciliation (409 if one is already running); `GET /sync/reconcile/latest` for the last report |
| `POST /sync/forward/start`, `stop`, `drain` | The copy into MongoDB. `drain` waits until every legacy commit made before the call is in MongoDB |
| `POST /sync/reverse/start?from=now` (or `from=resume`), `stop`, `drain` | The copy back |
| `POST /sync/target-sequence` | At the switch: new ids start above the legacy's |
| `POST /sync/legacy-sequence` | At a rollback: the legacy's ids start above the new ones |
| `/actuator/health`, `/actuator/prometheus` | Health and metrics |

## What it keeps in MongoDB

| Collection | What |
|---|---|
| `members` | The data (created by kitchensink-app) |
| `sync_offsets`, `sync_state`, `sync_markers` | Where each copy stopped, the current direction, drain markers |
| `migration_quarantine`, `reverse_quarantine` | Rows the other side refused, with the reason. The copy goes on |
| `reconciliations` | The reconciliation reports |

## Run it

Normally started by `ks start`. The legacy database needs `wal_level=logical`, a user with replication rights, and
a publication on `member` (see `local/src/main/resources/legacy-db/schema.sql`).

```bat
java -jar kitchensink-sync\target\kitchensink-sync-exec.jar
```

| Variable | Default | What |
|---|---|---|
| `MONGODB_URI` | `mongodb://localhost:27017/kitchensink` | The new database (a replica set) |
| `LEGACY_JDBC_URL`, `LEGACY_JDBC_USER`, `LEGACY_JDBC_PASSWORD` | `jdbc:postgresql://localhost:5432/kitchensink`, `kitchensink_sync`, empty | The legacy database |
| `SYNC_FORWARD_ENABLED`, `SYNC_REVERSE_ENABLED` | `true`, `false` | Which direction starts, before any switch was made |
| `SYNC_BATCH_SIZE`, `SYNC_PROBE_INTERVAL` | `2048`, `5s` | Batch size; how often the lag is measured |
| `SYNC_RECONCILE_SCHEDULED`, `SYNC_RECONCILE_INTERVAL` | `true`, `10m` | The scheduled reconciliation |
| `PORT` | `8081` | The HTTP port |

## Tests

```bat
.\mvnw.cmd -pl kitchensink-sync -am verify
```

They start their own PostgreSQL and MongoDB, and check the copy, the order of changes, replays, the quarantine, the
reconciliation, the copy back and restarts.
