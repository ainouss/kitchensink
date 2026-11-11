# The legacy kitchensink

The original JBoss EAP quickstart, **unchanged**: `src/` is a copy of
[`jboss-eap-quickstarts`](https://github.com/jboss-developer/jboss-eap-quickstarts), branch `8.1.x`, commit
`476de14`, folder `kitchensink/` (Apache License 2.0).

It is packaged as a **WildFly 42 bootable jar** (`target/kitchensink-legacy.jar`) that runs on **PostgreSQL**
instead of the quickstart's in-memory H2, as production would. Two configuration files differ, nothing else:

- `postgresql/META-INF/persistence.xml`: Hibernate no longer creates and drops the schema at each start
  (`hbm2ddl.auto=none`); the schema belongs to the database.
- the H2 datasource is left out; `wildfly/datasource.cli` defines a PostgreSQL datasource under the same name.

`ks start` runs it on port 8180, with 512 MB of heap like a default WildFly. To run it alone, with PostgreSQL
already running:

```bat
java -jar legacy\target\kitchensink-legacy.jar "-Djboss.socket.binding.port-offset=100"
```

It reads `LEGACY_DB_URL`, `LEGACY_DB_USER` and `LEGACY_DB_PASSWORD` (default: PostgreSQL on localhost:5432,
database and user `kitchensink`).
