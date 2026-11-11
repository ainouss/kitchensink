# characterization

The **legacy's behaviour, recorded as test cases**, and replayed against the new application on every build. It is
the safety net that was built before the new code: if a case passes on the legacy and fails on the new application,
the migration changed something.

## The cases

`cases/rest-members.json` holds 21 request and response cases on the REST API, one or more per rule of
[the legacy assessment](../docs/legacy-assessment.md): validation (R1 to R5, R10), the email already taken (R11), the
case-sensitive email (R4), unknown ids (R13), a client-supplied id (R7), the 200 on create (R9), the field order (R18).

Each case is a request, the expected status and the expected body. Differences that the business accepted are listed
once in `kitchensink-domain/src/main/resources/kitchensink/normalisation.json`, with their rule and reason.

## Run them

Against the **new application**, started inside the test on its own MongoDB. This runs on every build:

```bat
.\mvnw.cmd -pl characterization -am verify
```

Against the **running system**, while `ks start` runs:

| Command | Plays the cases on |
|---|---|
| `ks rules` | The facade: what users get, whichever application owns the data |
| `ks rules legacy` | The legacy itself, while it owns the writes |
| `ks rules new` | The new application itself, after the cutover |

The cases register members, so an application is only played on its own while it owns the writes: otherwise the
registrations would bypass the copy. Through the facade, while the dual-run is on, the new application's answers to
the reads are compared too. That is how a difference the cases did not check was found: on an id that is not a
number, the new application answered an error page instead of the legacy's empty 404. R13 now checks the body.

Against **any other URL** (a test environment, say):

```bat
.\mvnw.cmd -pl characterization -am verify "-Dkitchensink.target=<base URL>" "-Dtest=TargetCharacterizationTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

## To add a case

Add an entry to `cases/rest-members.json` with the request and the response recorded from the legacy, and name it after
its rule (`r5-phone-13`). `CaseFilesTest` checks the file is well formed.
