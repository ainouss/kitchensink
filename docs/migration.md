# How the data moves, and how the switch works

The goal: a large table that must not lose a single row, moved with as little downtime as possible, with a way
back at every step.

## The principle: one owner at a time

At any moment **one application owns the data**, and a copy runs the other way.

- **No dual writes** from the application: after a partial failure the two databases would differ, with no record of
  which one is right.
- **No dump and restore** in a maintenance window: the downtime grows with the volume, nothing is proven before the
  switch, and there is no way back.

## The five steps

| | Step | What happens |
|---|---|---|
| 1 | **Copy** | `ks sync`: the sync reads a consistent snapshot of the legacy table into MongoDB (the initial load), while the legacy keeps running. |
| 2 | **Stream** | Then, with no gap, it reads every change from PostgreSQL's log (change data capture, with Debezium) and applies it to MongoDB, in the order the legacy committed it. |
| 3 | **Prove** | The reconciliation compares every record in both databases. The dual-run sends live reads to both applications and compares the answers. |
| 4 | **Switch** | Writes pause for a few seconds, the copy catches up, both databases are checked identical, and the writes move to the new application. |
| 5 | **Keep the way back** | MongoDB's changes are copied back into PostgreSQL (change streams). A rollback loses nothing. |

The facade, in front of both applications, makes the switch a configuration change:
reads go to `LEGACY`, then `SHADOW_LEGACY` (both, the legacy answers), then `NEW`; writes go to `LEGACY`, then
`PAUSED` for a few seconds, then `NEW`.

The copy is started on purpose, not with the applications: it reads the whole table, and from then on PostgreSQL
keeps its log for the copy. In production that is a planned step, agreed with whoever runs the legacy database.

## Copying correctly

- **In commit order.** Changes are applied in the order the legacy committed them. For example, if one member gives
  up an email address and another takes it, the unique index must see it in that order.
- **Safe to replay.** Every document keeps the legacy's position for the change it reflects. A change that arrives
  twice, or late, is ignored. So a restart of the sync is harmless.
- **The commit position comes first.** A transaction that was open when the copy started commits *after* the
  snapshot, but its changes carry *earlier* positions. Compared on the change's position alone, it would have been
  skipped: a silent lost update. The sync compares the commit's position first. This was found while building it.
- **Nothing blocks the copy.** A row MongoDB refuses goes to a quarantine collection with the reason, and the copy
  goes on.
- **Watched.** The sync reports its lag (it writes a probe into the legacy every few seconds and times its arrival),
  and the size of the log PostgreSQL keeps for it.

## Proving the copy

- **Reconciliation** (`ks reconcile`): the counts, then a hash of every record on both sides, streamed by id. A
  difference is checked again after a few seconds, so changes still in flight are not reported. It takes about
  a quarter of a second for 10,000 members.
- **Dual-run** (`ks dual-run`): the facade asks both applications, answers with the legacy's response, and compares
  the other one in the background. Every difference is stored.

## The switch (`ks cutover`)

Five steps: **check, pause, catch up, flip, watch**.

| Step | What | If it fails |
|---|---|---|
| 1 **Check** | The copy runs with less than 5 s of lag; at least 1,000 reads compared with no difference; reconciliation green. It also says whether `ks rehearse` ran on this machine: optional, it measures the pause and the way back first. Then it asks you to type `yes`. | stop, nothing changed |
| 2 **Pause** | Writes paused: the facade answers `503` with `Retry-After`. Reads go on. | writes back to the legacy |
| 3 **Catch up** | A marker written in the legacy's log has arrived, so every commit is in MongoDB; both databases are identical (same counts, same hashes) | writes back to the legacy |
| 4 **Flip** | The copy turns around: copy to MongoDB stopped, new ids start above the legacy's, copy back (MongoDB → PostgreSQL) started. Then reads and writes go to the new application, and the pause ends | before the traffic moves: writes back to the legacy; after: rollback |
| 5 **Watch** | A test registration arrives in both databases; the copy back runs with less than 5 s of lag | rollback |

Reads never stop. Writes pause from step 2 until the flip ends.

## The way back (`ks rollback`)

The same steps, without the check:

1. **Pause** the writes.
2. **Catch up**: wait until every MongoDB write is in PostgreSQL.
3. **Flip back**: move the legacy's id sequence above every new id, stop the copy back, restart the copy into
   MongoDB, check both databases are identical, and give the writes back to the legacy.
4. **Watch**: a test registration arrives in both databases.

If a step fails, writes **stay paused on purpose**: the databases are not known to agree. Fix the cause and run
`ks rollback` again: it continues where it stopped.

## The numbers

`ks rehearse` switches and rolls back under live traffic (4 readers, 2 writers), and checks that every registration
a client was told succeeded is in both databases. With 10,000 members, on a machine with 2 CPU cores, over 4 runs:

| Switch: writes paused | Rollback: writes paused | Registrations during a run | Lost |
|---|---|---|---|
| 1.5 to 2.1 s | 1.7 to 1.8 s | 1,600 to 2,300 | 0 |

The initial load takes a few seconds (`ks sync` shows it), and the reconciliation a quarter of a second. Inside the pause, the drain and stopping
the copy take about a second; the identical check, a quarter of a second. A client that gets a `503` retries after
the `Retry-After` delay, so users see a short wait, not an error. Run `ks rehearse` on your machine to get its own
numbers; each run is saved in `.local/rehearsals/`.

## What grows with the table

| | At 10,000 members (measured) | It grows with | With 100 million members | What we would do |
|---|---|---|---|---|
| Initial load | a few seconds | the rows | hours, with the legacy online | Faster batches; split by id range |
| Reconciliation | about 0.25 s | the rows | minutes | Split by id range, in parallel |
| Write pause | about 2 s | the rows, through the identical check | minutes: too long | Check only the records changed since the last green reconciliation |
| PostgreSQL's log kept for the copy | small | the time the copy is stopped | a stuck copy fills the disk | Alert on it |

The copy costs no downtime at any size. The pause does: limiting the final check to recent changes is the next
improvement.

## What the legacy database needs

`wal_level=logical`, a user with replication rights, and a publication on the table. Nothing in the legacy
application changes.

## At the end

Decommissioning is a decision of its own, after a stable period: stop the copy back, archive the table, drop the
replication slot and the publication, and delete `kitchensink-sync` and `kitchensink-facade`.
