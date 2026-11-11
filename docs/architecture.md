# How the new application is built

## The stack

- **Java 21** and **Spring Boot 4.1**, the latest stable release.
- **MongoDB 8.0** through Spring Data MongoDB.
- The servlet stack with **virtual threads**: every request is a short call to one database, so plain blocking code
  scales well without the cost of reactive code.

## One application, with clear boundaries inside

The new kitchensink is **one deployable** (a modular monolith), organised by business capability: `member`.

```
member/
  domain/        Member, its validation rules. Plain Java, no Spring, no MongoDB.
  application/   The use cases: register a member, find members. Ports to the outside.
  adapter/in/    The web page, the legacy REST API, the new versioned API.
  adapter/out/   MongoDB.
```

Tests check these boundaries on every build (ArchUnit): the domain depends on nothing, the use cases do not know
the adapters, and adapters do not call each other.

Why not microservices? There is one capability, one team and one database. A network boundary would add failure
modes and buy nothing. The boundaries are there, so a capability can become a service the day it needs to.

The migration tools (`kitchensink-sync`, `kitchensink-facade`) are separate deployables: they run once, scale
differently, and are deleted when the migration is over.

## The data model: one document per member

```json
{
  "_id": 1301,
  "name": "Agent 007",
  "email": "007@mailinator.com",
  "phoneNumber": "0612345678",
  "schemaVersion": 1,
  "quality": ["NAME_HAS_DIGIT"],
  "sync": { "origin": "LEGACY", "txLsn": 23898100, "lsn": 23898312 }
}
```

| Field | Choice | Why |
|---|---|---|
| `_id` | the legacy's numeric id | It is public: in URLs and in clients' data. Keeping it means no lookup table, and identical answers from both applications during the dual-run. |
| `phoneNumber` | a string | `0612345678` is not a number: a numeric type drops the leading zero. |
| `schemaVersion` | `1` | Lets documents evolve later without a big-bang migration. |
| `quality` | the rules an old row breaks | About 0.5 % of the legacy's rows break today's rules. They are kept and flagged, never refused or silently changed. |
| `sync` | the source's position in its log | Lets the sync ignore a change it has already applied (see [migration](migration.md)). |

**Validation** is in two places, on purpose:
- the **structure** (required fields, types) in a MongoDB `$jsonSchema` validator;
- the **business rules** (lengths, digits, email format) once, in the domain, applied to new writes. In the database
  they would refuse the legacy's old rows during the copy.

**Indexes** come from the access patterns: a unique index on `email` (case-sensitive, like the legacy), and one on
`name, _id` with an English collation for the member list, sorted like the legacy sorts it.

**Ids**: new ids come from a counter document. At the switch it is set above the legacy's last id; on a rollback,
the legacy's sequence is moved above the new ids. Both steps are part of the cutover.

## Two APIs

- **`/rest/members`**, the legacy contract, **unchanged**: same paths, same answers, same quirks (200 with an empty
  body on create, its error format). Clients do not have to change anything during the migration, and the facade can
  send any request to either application.
- **`/api/v1/members`**, the modern contract beside it: 201 with `Location`, errors as RFC 9457 problem details,
  paging, and an OpenAPI document (`/swagger-ui.html`). Clients move to it when they are ready; `/rest` is removed
  after the last one.

## Proving it behaves the same

1. **The characterization suite** (`characterization/`): the legacy's behaviour at its edges, rules R1 to R18 of
   the [assessment](legacy-assessment.md), recorded as request and response cases. Every build replays them against
   the new application, and they can be replayed against the running legacy.
2. **The reconciliation**: every record compared in both databases.
3. **The dual-run**: live reads answered by both applications and compared.

The differences the business accepted are listed once, in `kitchensink-domain/.../normalisation.json`, each with
its rule and reason. The suite and the dual-run both read it. Any other difference is a bug.
