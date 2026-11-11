# kitchensink-app

The **new kitchensink**: Spring Boot 4 on Java 21, data in MongoDB. It does everything the legacy did, with the same
page, the same REST API, and a new versioned API beside it.

## What it serves

| Path | What |
|---|---|
| `/` (also `/kitchensink/`) | The registration page: the form and the member list, paged and sorted by name, in English, Spanish or French |
| `/rest/members` (also `/kitchensink/rest/members`) | The **legacy API, unchanged**: same answers, same quirks (200 on create, its error format) |
| `/api/v1/members` | The **new API**: `POST` returns 201 with `Location`, `GET ?page=&size=` is paged, errors are RFC 9457 problem details |
| `/swagger-ui.html` | The new API's documentation |
| `/actuator/health`, `/actuator/prometheus` | Health and metrics |

## How the code is organised

```
member/
  domain/        from kitchensink-domain: the member and its rules
  application/   the use cases: RegisterMember, FindMembers, and the ports they need
  adapter/in/    web (the page, the legacy API) and api (the new API)
  adapter/out/   mongo (the members collection, the id counter)
```

`ArchitectureTest` checks these boundaries on every build. More in [the architecture](../docs/architecture.md).

## Data

One document per member in the `members` collection, with the legacy's id as `_id`. At start, the application
creates the collection, its validator and its indexes if they are missing. New ids come from the `sequences`
collection.

## Run it

Normally started by `ks start`. To run it alone, against a MongoDB replica set:

```bat
java -jar kitchensink-app\target\kitchensink-app-exec.jar
```

| Variable | Default | What |
|---|---|---|
| `MONGODB_URI` | `mongodb://localhost:27017/kitchensink` | The database (a replica set) |
| `KITCHENSINK_SEED` | `true` | Adds the quickstart's sample member to an empty database. `ks start` sets `false`: the data comes from the legacy |
| `PORT` | `8080` | The HTTP port |

## Tests

```bat
.\mvnw.cmd -pl kitchensink-app -am verify
```

They start their own MongoDB. They cover the page, both APIs, the persistence and the architecture rules.
