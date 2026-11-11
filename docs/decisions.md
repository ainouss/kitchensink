# The main decisions and their trade-offs

| Decision | Instead of | Why | The price |
|---|---|---|---|
| **Spring Boot 4.1 on Java 21** | Quarkus | Both would do. Spring Boot is the most common enterprise target, with Spring Data MongoDB and a large hiring pool. | Slower start than Quarkus, irrelevant here |
| **Virtual threads, blocking code** | Reactive (WebFlux) | Short calls to one database: blocking code scales with virtual threads, and stays simple to read and test. | None for this workload |
| **One application with boundaries inside** | Microservices | One capability, one team, one database. The boundaries are checked by tests, so a part can leave later. | More structure than twelve classes need, on purpose |
| **One document per member, legacy id kept** | ObjectId plus a `legacyId` field | The id is public. Keeping it avoids a lookup table forever, and makes both applications answer identically. | Ids come from a counter, managed at the switch |
| **Structure in the database, rules in the code** | All rules in `$jsonSchema` | The rules would refuse the legacy's old rows, and two copies of a rule drift apart. | The database alone does not enforce the business rules |
| **Legacy API kept as is, new API beside it** | Fixing the old API's quirks now | Clients change nothing during the migration, and the facade can compare answers. | Two APIs until the last client moves |
| **One owner at a time, change data capture, a short write pause, a copy back** | Dump and restore; dual writes; a key-by-key handover | Nothing is lost, everything is proven before the switch, and the way back stays open. | A few seconds of 503 at each switch |
| **Debezium, embedded in the sync** | Kafka Connect; MongoDB Relational Migrator | One table, one consumer: the same connector with nothing else to run. At enterprise scale, Kafka Connect or Relational Migrator would carry most tables. | Not highly available: restarted by the platform, and safe to restart |
| **Characterization tests first** | Rewriting from the specification | The legacy's real behaviour, quirks included, is the specification. | Recording the cases takes time up front |
| **Real databases in tests** | Mocks or in-memory fakes | Sorting, unique indexes, transactions and change streams only behave truly on the real thing. | A slower build (a few minutes) |
| **Everything runs with only a JDK** | Docker Compose | Runs on a locked-down laptop: the databases start from downloaded binaries. | Not how production runs: there, the databases are managed services |
