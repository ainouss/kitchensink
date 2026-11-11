# kitchensink-facade

The **router in front of both applications**: users always call the facade, and it decides which application answers.
This is what makes the switch a configuration change. It lives until the legacy is retired; in production its job
moves to the API gateway.

## What it does

A **control document** in MongoDB says where reads and writes go:

| Reads | Meaning |
|---|---|
| `LEGACY` | The legacy answers |
| `SHADOW_LEGACY` | The legacy answers; the new application is asked too and its answer compared (the **dual-run**) |
| `SHADOW_NEW` | The new application answers; the legacy is asked too and compared |
| `NEW` | The new application answers |

| Writes | Meaning |
|---|---|
| `LEGACY` | Writes go to the legacy |
| `PAUSED` | Writes get `503` with `Retry-After`, a few seconds during a switch |
| `NEW` | Writes go to the new application |

In shadow mode the comparison runs in the background: users never wait for it. Answers are compared after removing
the accepted differences (`normalisation.json` in kitchensink-domain); every other difference is stored.

## Its API

| Call | What |
|---|---|
| `/kitchensink/**` | Forwarded unchanged to the application the control names |
| `GET /facade/control` | The current control |
| `PUT /facade/control` | Changes it. Needs `Authorization: Bearer <admin token>` |
| `GET /facade/shadow` | The dual-run's totals: matched, mismatched, accepted |
| `/actuator/health`, `/actuator/prometheus` | Health and metrics |

`ks dual-run`, `ks cutover` and `ks rollback` use this API. `ks start` makes the admin token and keeps it in
`.local/admin-token`.

## What it keeps in MongoDB

| Collection | What |
|---|---|
| `migration_control` | The control document |
| `dualrun_mismatches` | Every difference the dual-run found, with both answers |

## Run it

Normally started by `ks start`.

```bat
java -jar kitchensink-facade\target\kitchensink-facade-exec.jar
```

| Variable | Default | What |
|---|---|---|
| `LEGACY_URL` | `http://localhost:8180` | The legacy application |
| `NEW_URL` | `http://localhost:8080` | The new application |
| `FACADE_ADMIN_TOKEN` | empty: the control is read only | The token that allows changing the control |
| `MONGODB_URI` | `mongodb://localhost:27017/kitchensink` | Where the control and the differences are kept |
| `PORT` | `8000` | The HTTP port |

## Tests

```bat
.\mvnw.cmd -pl kitchensink-facade -am verify
```
