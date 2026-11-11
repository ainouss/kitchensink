# local: the `ks` tool

Runs the whole migration on one machine, with **only a JDK**: no Docker, no database to install. The tests of the
other modules use its databases too.

## Commands

On Windows `.\ks <command>`, on macOS or Linux `./ks <command>`, from the repository's root.

| Command | What it does |
|---|---|
| `ks start` | Starts everything and stays running; Ctrl+C stops it. The first start creates the legacy's data (10,000 members); MongoDB stays empty until `ks sync` |
| `ks reset` | Deletes the data and the logs, so the next `ks start` creates the same members again. Keeps the rehearsal reports |
| `ks sync` | The initial load into MongoDB, then every change, and shows the load's progress. Once started, the sync restarts with `ks start`. `dual-run`, `reconcile` and `rehearse` need it |
| `ks rules [legacy\|new]` | Plays the characterization cases (`characterization/cases/`) through the facade, or on one application while it owns the writes: the cases register members |
| `ks status` | Where reads and writes go, the state of both copies, the dual-run's totals |
| `ks dual-run [--seconds 30]` | Sends reads to both applications through the facade and compares the answers |
| `ks reconcile` | Compares every record in both databases |
| `ks rehearse` | Switch, 20 s on the new application, rollback, all under live traffic; checks nothing was lost |
| `ks cutover [--yes] [--watch 30]` | The switch, step by step, each step checked. Asks for `yes` unless `--yes` |
| `ks rollback [--yes]` | The way back. If a step fails, writes stay paused; run it again to continue |
| `ks traffic [--seconds 60] [--writers 2]` | Readers and writers through the facade, like real users |
| `ks stop` | Stops everything `ks start` runs, even if its window was closed |

## What `ks start` runs

| What | Port | How |
|---|---|---|
| PostgreSQL 17, the legacy's database | 5432 | Binaries from Maven (zonky embedded-postgres), with logical WAL and an ICU English collation |
| MongoDB 8.0, a single-node replica set | 27017 | Downloaded on first start (flapdoodle), then cached |
| The legacy on WildFly | 8180 | `legacy/target/kitchensink-legacy.jar` |
| The new application | 8080 | `kitchensink-app/target/kitchensink-app-exec.jar` |
| The sync | 8081 | `kitchensink-sync/target/kitchensink-sync-exec.jar` |
| The facade | 8000 | `kitchensink-facade/target/kitchensink-facade-exec.jar` |

Everything listens on this machine only. The data, the logs, the admin token and the rehearsal reports are in
`.local/` at the repository's root.

`ks start` writes each process it starts to `.local/pids`. If a run ends badly (its window closed, the process
killed), its processes keep running and lock the data. The next `ks start` stops them first, and `ks stop` stops them
on demand. A second `ks start`, or a `ks reset`, is refused while one runs.

## The code

| Class | What |
|---|---|
| `Ks` | The commands |
| `Stack` | `ks start` and `ks reset`: starts the databases and the four services, waits for each, stops them all on Ctrl+C |
| `StartSync` | `ks sync`: starts the copy into MongoDB and follows the initial load |
| `Rules`, `Cases`, `CaseRunner` | `ks rules`; the case loader and player, shared with the characterization tests |
| `Leftovers` | `ks stop`, and what an earlier run left running |
| `LegacyDatabase` | PostgreSQL with the legacy schema and its generated data (`src/main/resources/legacy-db/`) |
| `LocalMongo` | MongoDB as a replica set |
| `Switch` | `ks cutover` and `ks rollback` |
| `Rehearsal`, `Traffic` | `ks rehearse`, and the simulated users |
| `Api` | Calls to the facade's and the sync's APIs |

## Build

```bat
.\mvnw.cmd -pl local -am package -DskipTests
```

`ks.cmd` and `ks` at the repository's root run `local/target/kitchensink-local-exec.jar`.
