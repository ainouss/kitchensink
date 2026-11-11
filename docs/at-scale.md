# How this approach scales to a large application

kitchensink is one table and three entry points. It was migrated the way a 2-million-line application would be,
so the same steps apply at scale.

## Break the problem into slices

1. **Assess first**: inventory the entry points, the tables, and which code reads and writes each table (the CRUD
   matrix). Write down every hidden rule ([example](legacy-assessment.md)).
2. **Slice by business capability**, following the CRUD matrix: each slice owns its tables and moves as one unit.
   One epic per capability, one story per entry point, one reviewable pull request per story.
3. **Build the safety net before the new code**: characterization tests recorded from the legacy, run against both
   systems. The count of open differences is the progress metric.
4. **Move each slice behind the facade**, with its data copied, proven and switched as in [migration](migration.md).
   Every step can be undone.
5. **Deliver value with each slice**, so the sponsor sees progress: a faster page, a new API, a server switched off.

## The scaffolding a large migration needs

- **A routing facade** (in production: the API gateway or the service mesh) to move traffic slice by slice.
- **A data pipeline** per slice: snapshot, change data capture, reconciliation, copy back. At scale, Kafka Connect
  for many tables and consumers, or MongoDB Relational Migrator to map a large schema.
- **One gate for every change**: build, unit tests, characterization suite, architecture rules.
- **Observability**: lag of every copy, reconciliation results, dual-run differences, error rates and latency of
  both systems. Cutover triggers agreed in advance (error rate, latency, reconciliation drift).
- **Rehearsals** on a copy of production data, masked, before every switch.

## Using AI, safely

AI made this faster at every step: reading the legacy, listing its rules, proposing test cases, writing code and
documentation. It does not replace the checks; it is held by them:

1. The model **proposes** a change: a translation, a test, a mapping.
2. The **compiler and the tests decide**: the build, the characterization suite, the reconciliation, the dual-run.
3. Failures go back to the model with the exact error, until the checks pass.
4. **A person reviews** every change and must be able to explain every line.

What makes it scale:
- **Context from an index, not from reading files**: a parsed view of the code (symbols, call graph, CRUD matrix), so
  the model gets exactly the slice it needs.
- **Small, reviewable changes**: one story, one pull request.
- **Checks generated per component**: the same three proofs (characterization, reconciliation, dual-run) for every
  slice.
- **Governance**: no production data in prompts, and every change traceable to its story, its tests and its review.

## What we would add with more time

- A shorter write pause: check only the records changed since the last green reconciliation.
- A performance baseline of both applications on the same scenarios.
- Production setup: a three-node MongoDB replica set (or Atlas) with TLS and backups, secrets from a vault,
  dashboards and alerts on the metrics every service already exposes.
