# What the legacy does, rule by rule

The first step of any migration: know exactly what the old system does, including what nobody wrote down.

kitchensink is small: one table, three entry points, about 1,300 lines. The work is in the **rules hidden in
annotations, error handling and the database**. They are listed below, and each one is a test case in the
characterization suite. Two of them (R17, R18) were found by running the legacy, not by reading it.

## Sources

- **Code**: `jboss-developer/jboss-eap-quickstarts`, branch `8.1.x`, commit `476de14`, folder `kitchensink/`
  (copied unchanged in [`legacy/`](../legacy)).
- **Database**: the quickstart uses an in-memory H2. Production is assumed to be PostgreSQL, so `legacy/` runs the
  same code on PostgreSQL, with 10,000 generated members.

## Inventory

| Entry point | What it does |
|---|---|
| `GET /kitchensink/index.jsf` | The JSF page: a registration form and the list of members, sorted by name |
| `GET /kitchensink/rest/members` | Every member, sorted by name |
| `GET /kitchensink/rest/members/{id}` | One member, 404 if absent |
| `POST /kitchensink/rest/members` | Validates, checks the email is free, registers |

No message queue, no scheduled job, no file exchange, no call to another system.

One table, `member` (id, name, email unique, phone_number), and a sequence that hands out ids in blocks of 50.
**One writer** (the registration service) and several readers: the table has a clear owner and moves as one unit.

## The rules

"Keep" means the new application behaves the same, even where the legacy is odd, until the business decides
otherwise. A behaviour change is never mixed into the migration.

| # | Rule | Decision |
|---|---|---|
| R1 | Name required, 1 to 25 characters | Keep |
| R2 | Name must not contain a digit (the message wrongly says "letters and spaces only") | Keep, message included |
| R3 | Email required, valid format (`a@b` is valid) | Keep, same validator |
| R4 | Email unique, **case-sensitive** (`John@x.io` and `john@x.io` can coexist) | Keep; question for the business |
| R5 | Phone required, 10 to 12 digits only | Keep |
| R6 | The id is chosen by the server, numeric | Keep the same public ids |
| R7 | A client-supplied id fails with 400 | Keep the 400 (its message is an accepted difference) |
| R8 | List sorted by name, in the database's collation; ties in no particular order | Make it explicit: name, then id |
| R9 | `POST` succeeds with **200 and an empty body** (not 201) | Keep on `/rest`; `/api/v1` answers 201 |
| R10 | Validation error: 400 with `{field: message}` | Keep |
| R11 | Email taken: **409 `{"email": "Email taken"}`** | Keep |
| R12 | Two registrations racing with the same email: the second gets a 400 | **Fix**: 409, like R11 (accepted difference) |
| R13 | Unknown id, or an id that is not a number: 404 with an empty body | Keep |
| R14 | After a registration the list refreshes; the form keeps its values on error. On a taken email, the page shows the database's raw error (`duplicate key value violates unique constraint "uk_member_email"`) | Keep the behaviour; the new page says "Email taken" instead of a PostgreSQL message (accepted difference) |
| R15 | Each registration logs the member's name | **Fix**: no personal data in logs (accepted difference) |
| R16 | Page in English, Spanish and French, from the browser's language | Keep |
| R17 | **`GET /rest/members` and the page load every member in memory at once**: memory grows with the table, and a large table makes them answer 500 (out of memory) | **Fix**: the new one streams the list and pages the page; clients are steered to the paged `/api/v1` |
| R18 | JSON fields come in alphabetical order | Keep on `/rest` |

## The data

The generated legacy data looks like old production data:
- 10,000 members;
- names with accents and mixed case, so sorting depends on the collation;
- about 0.5 % of rows that break today's rules (a digit in the name, a name too long, a phone with dashes, a bad
  email, emails that differ only by case). They exist, users see them, and the new database must keep them.

## Path for each part

| Legacy part | Path |
|---|---|
| `Member` + JPA + relational database | **Re-architect**: a document model for MongoDB |
| `MemberRegistration` (EJB) | **Refactor** into a use case |
| REST resource | **Re-architect, contract kept**, with a new versioned API beside it |
| JSF page | **Rebuild** in Thymeleaf: same fields, messages and languages |
| CDI producers, JAX-RS activator | **Retire**: the framework does it |
| Integration tests | **Keep** as the first characterization cases |

## Risks

| Risk | Mitigation |
|---|---|
| Sorting differs between PostgreSQL and MongoDB (R8) | Explicit sort; the same ICU English collation on both sides; the dual-run shows any difference |
| Duplicate ids after the switch | The id counter is set above the legacy's at the switch, and the reverse on rollback |
| Old rows refused by the new validation | Validation applies to new writes only; old rows are kept and flagged |
| The full list fails on a large table (R17) | Streamed in the new application; the dual-run compares single members instead |
| The write pause | Measured in rehearsal; clients get 503 with `Retry-After` and retry |
