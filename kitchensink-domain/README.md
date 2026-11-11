# kitchensink-domain

The **member** and its **validation rules**, in plain Java. No Spring, no MongoDB: every other module can use it, and
it is tested in milliseconds.

## What is inside

| Class | What |
|---|---|
| `Member` | A registered member: id, name, email, phone number |
| `NewMember` | A registration request, with the legacy's rules as Jakarta Validation annotations |
| `MemberRules` | Lists the rules an existing row breaks: its quality flags |
| `Quality` | The rules an old row can break: `NAME_HAS_DIGIT`, `NAME_LENGTH`, `EMAIL_SYNTAX`, `PHONE_FORMAT` |
| `EmailTakenException` | The email is already registered (rule R11) |

## The rules

The same as the legacy, quirks included (see [the legacy assessment](../docs/legacy-assessment.md)):

| Rule | Field | Constraint |
|---|---|---|
| R1 | name | required, 1 to 25 characters |
| R2 | name | no digit (the message wrongly says "letters and spaces only", kept as is) |
| R3 | email | required, valid format |
| R5 | phone number | 10 to 12 digits |

The messages are in English, Spanish and French: `src/main/resources/ValidationMessages*.properties`.

## Shared files

`src/main/resources/kitchensink/normalisation.json` lists the **accepted differences** between the legacy and the new
application, each with its rule and the reason. The characterization suite and the facade's dual-run both read it.
Any other difference is a bug.

## Tests

```bat
.\mvnw.cmd -pl kitchensink-domain verify
```
