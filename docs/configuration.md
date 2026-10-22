# Configuration and profiles

Select exactly one Spring profile explicitly: `dev`, `test` or `hosted`. Startup
fails with no active profile, an unknown profile, or a combination of profiles.
There is no default `dev` selection and no hosted fallback to H2. Select the
profile through an IDE, `SPRING_PROFILES_ACTIVE`, or a Spring Boot command-line
option; do not combine conflicting selections.

The shared [application.properties](../src/main/resources/application.properties)
contains application, port, JWT, payment-adapter, logging and seed settings. It
contains no datasource settings. Database choices belong to the profile files.

| Profile | Database and schema | Payment adapter | HTTP binding |
|:--------|:--------------------|:----------------|:-------------|
| `dev` | H2 in-memory `greenfield-dev`; `create-drop` | Mock by default; historical real adapter requires explicit opt-in | `127.0.0.1` |
| `test` | A generated H2 database name per application context; `create-drop` | Mock only; demo seeding is rejected | `127.0.0.1` |
| `hosted` | Required PostgreSQL connection; existing schema validated | Mock only | `0.0.0.0` |

`PORT` selects the HTTP port in every profile and defaults to `8080`. H2 dialect
detection is automatic; do not add the old PostgreSQL/H2 dialect workaround.
H2 consoles and SQL initialization scripts are disabled in all three profiles.
The default framework logging avoids SQL, bind-value and request-detail debug
output. Task 7 removed raw webhook payload/signature logging and disabled real
webhook mutation; historical provider operation remains unverified.

Task 7 adds the `payment_collections` table and `payments.accounting_version` and
`payments.row_version` columns. Disposable dev/test schemas create these through
JPA. Hosted remains `ddl-auto=validate`: a later reviewed database migration must
create the ledger, constraints and columns before a hosted deployment. Do not
switch hosted to automatic schema updates. See [the schema requirements](payment-events.md#database-and-migration-requirements).

Payment simulation defaults off. Only in dev with mock mode, set
`DEMO_PAYMENT_SIMULATION_ENABLED=true` (property
`app.demo.payments.simulation.enabled`) to register the super-admin-only endpoint.
Existing private JWT and account credentials are still required; no shared key or
public super-admin password is supplied. Other profiles and real mode never
register it, and the processor rechecks the environment and database role on
every invocation. See [simulation usage](payment-events.md#development-only-simulation).

## Local development

Use Java 17 and Maven. First set `JWT_SECRET` privately using the
[PowerShell or Bash commands in the README](../README.md#2-set-environment-variables).
They generate 32 random bytes, encode them as Base64 and assign the result to the
current shell without printing it. No OnePipe or PostgreSQL settings are needed
for the local mock.

```sh
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

This explicitly selects [application-dev.properties](../src/main/resources/application-dev.properties).
The schema and records are temporary; stopping the application loses H2 data.
There are no bundled privileged-account passwords. Demo seeding stays disabled
unless explicitly enabled under `dev` and supplied with all required seed settings.

Spring Boot does not automatically load a `.env` file or the repository's
[application-example.properties](../application-example.properties). That file is
a reference across several profiles, not a configuration to import wholesale.
Keep private values outside tracked files. Retaining the same JWT key allows
previously issued tokens to remain verifiable until they expire; generating a
new key invalidates them.

## IntelliJ

Open the Spring Boot run configuration for `OnepipeBoardingSchoolApplication`.
Set **Active profiles** to `dev`, select a Java 17 runtime, and supply a privately
generated `JWT_SECRET` through **Environment variables**. Leave OnePipe settings
unset for mock development. Do not save or share secrets in a checked-in run
configuration, screenshot or example.

An environment variable set in a separate terminal is not automatically added to
an already running IDE. Configure the private variable in the run configuration,
or launch the IDE from an environment that already contains it. No driver or
dialect program arguments are needed.

## Tests

```sh
mvn test
```

Application-startup tests explicitly select `dev` or `test` and provide transient,
generated signing keys. Other focused tests construct only the components they
exercise. No PostgreSQL instance, provider credentials or shared test JWT is
required. [application-test.properties](../src/main/resources/application-test.properties)
sets no fixed datasource URL: Boot generates an isolated H2 database name for each
context. Schema creation and cleanup use `create-drop`.

The `test` profile rejects real-provider and demo-seeding overrides. Do not use it
for a persistent demonstration. Passing the suite does not establish the
historical provider contract, webhook safety, authorization or payment-state
correctness outside the tested behavior.

## Hosted PostgreSQL

The optional [application-hosted.properties](../src/main/resources/application-hosted.properties)
requires all of these private environment settings:

| Setting | Requirement |
|:--------|:------------|
| `SPRING_PROFILES_ACTIVE` | Set to `hosted`, unless selected explicitly through the launch command |
| `DB_URL` | A PostgreSQL JDBC URL, including any SSL/connection options required by that database |
| `DB_USERNAME` | A nonblank database username |
| `DB_PASSWORD` | A nonblank database password |
| `JWT_SECRET` | A private Base64 signing key containing at least 32 random bytes |
| `PORT` | Optional HTTP port; defaults to `8080` |

No JDBC URL or database credential has a fallback. The driver is fixed to
`org.postgresql.Driver`; `DB_DRIVER_CLASS` is not used. The pool allows at most five
connections, retains at least one idle connection, waits up to 30 seconds for a
connection, and uses a five-second validation timeout. Hosted HTTP binds to
`0.0.0.0` and honors `PORT`, so the application can accept traffic from a hosting
platform such as Render. Real-provider overrides are rejected.

Connection-identity overrides under `spring.datasource.hikari.*` are rejected,
including alternate JDBC URLs, drivers and credentials. Hikari would otherwise
apply these after the standard datasource settings. Keep hosted connection
details in `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`; use the Hikari namespace only
for pool configuration.

Once an application JAR has been built and the private environment is supplied,
the profile can be selected with:

```sh
java -jar target/onepipe-boarding-school-1.0-SNAPSHOT.jar --spring.profiles.active=hosted
```

The profile uses `spring.jpa.hibernate.ddl-auto=validate`. It checks an existing
compatible schema and does not create or update tables. A new, empty database
will fail schema validation. Schema migrations are a later task and are a
prerequisite for initializing a fresh hosted demo; there is no Flyway/bootstrap
step in this change.

This configuration work has not provisioned a database, recovered the expired
database, changed a Render service, deployed the application or verified a hosted
connection. The old hosted database and internship credentials are not required
or assumed to work.

PostgreSQL persistence retains application records, while the mock adapter's
simulated merchant and invoice state still disappears on application restart.
An older database payment can therefore receive `NotFound` from the restarted
mock. See [mock behavior](mock-onepipe.md) for that boundary.

## Demo seeding

Ordinary startup must not create privileged accounts with publicly known
credentials. The automatic `DataSeeder` component therefore requires both the
`dev` profile and `DEMO_SEED_ENABLED=true`. It is excluded when `test` or `hosted`
is active, including mixed selections; the existing environment validator still
rejects mixed profiles and any test-profile seeding override. Hosted excludes the
seeder even if its opt-in flag and credentials are accidentally supplied.

For an explicitly requested local seed, supply these privately in the shell or
IntelliJ run configuration before launching with the normal `dev` instructions:

| Setting | Meaning |
|:--------|:--------|
| `DEMO_SEED_ENABLED` | Set to `true` to request initialization |
| `SUPER_ADMIN_EMAIL` | Stable super-admin identity, distinct from the branch-admin email |
| `SUPER_ADMIN_PASSWORD` | Password to hash when creating a new super-admin |
| `DEMO_BRANCH_ADMIN_PASSWORD` | Password for a new branch-admin, or the existing password for orphan recovery |
| `JWT_SECRET` | The application's usual private signing key; not a seed credential |

All three account settings must be nonblank before repository reads or writes. Passwords are
hashed through the existing BCrypt encoder; none are published, printed, or
supplied as reusable defaults. Existing user emails, password hashes and roles
are never changed by initialization. An existing super-admin is accepted only
with `SUPER_ADMIN` role and no branch assignment. Changing a configured password
does not rotate the stored password or grant access to an existing account.

The stable branch-admin identity is `branch-admin@example.invalid`. The fixture
keeps the original synthetic campus details and reserves these unique fields:

| Branch field | Reserved demonstration identifier |
|:-------------|:----------------------------------|
| `requestRef` | `DEMO-BRANCH-REF` |
| `transactionRef` | `DEMO-BRANCH-TXN` |
| `rcNumber` | `DEMO-RC-001` |

The super-admin email is trimmed and lowercased for lookup and creation. Keep it
stable between runs: changing it targets another identity, not an account rename.
Repeated calls query the database using these identities and existing uniqueness
constraints, reuse completed records and preserve the existing association. No
in-memory completion flag is used, and no provider requests or payment fixtures
are generated.

The public Spring-managed `DataSeeder.run` method is transactional. The startup
runner calls it through Spring's transaction proxy, rather than self-invoking a
private annotated method. Configuration and all discovered identity conflicts
are checked before any writes. New administrators, branch creation, and any
relationship repair share one transaction. A failure rolls back every change
from that attempt, including when a uniqueness constraint fails at commit.

Partial-state handling is deliberately conservative:

| Existing state | Result |
|:---------------|:-------|
| Valid super-admin only | Preserve it; create the missing branch-admin and branch |
| Branch-admin with no branch | Require `BRANCH_ADMIN` and its current password; create the missing branch |
| Complete matching branch and branch-admin, missing super-admin | Preserve the branch and its owner; create the configured super-admin |
| Fully matching synthetic branch with no owner | Link a new demo branch-admin, or an existing orphan only after password verification |
| Unexpected role, another owner/branch, ambiguous email, split identifiers, or changed fixture data | Fail without overwriting existing data |

An email match alone cannot justify attaching an account to a branch. An
orphaned branch-admin must prove ownership through the supplied current password
and must have no other branch assignment. A completed fixture must already belong
to that same database user and match all synthetic fixture fields. The seeder
never promotes a user, resets a password, reassigns another owner's branch, or
rewrites edited branch details to force a match. If ownership cannot be proved,
initialization stops and the records require deliberate manual investigation.

Concurrent attempts can encounter an existing uniqueness constraint and fail;
the failed attempt rolls back. There is no distributed initialization lock or
automatic retry. H2 remains disposable, and repeated seeding within a running
database does not make dev data persistent. This does not implement hosted
recruiter accounts, hosted schema initialization, or database migrations.

These remain synthetic portfolio fixtures. Student/payment authorization is
described in the [access policy](authorization.md). Profile separation does not
repair the existing public webhook, durable webhook idempotency or legacy payment
calculations documented in the [README](../README.md#security-and-limitations).
