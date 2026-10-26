# PostgreSQL schema and migrations

Task 9 provides a complete initial PostgreSQL schema for an **empty database or
empty application schema**. Hosted startup runs Flyway before Hibernate validates
the mappings. It does not use Hibernate `create` or `update`, create a database,
seed accounts, contact OnePipe, or deploy a service.

The existing Spring Boot 3.1.6 dependency management selects Flyway **9.16.3**.
That version includes PostgreSQL support in `flyway-core`; a separate current
Flyway PostgreSQL module is not required. Java 17 is unchanged. See the
[Spring Boot 3.1.6 Flyway initialization guide](https://docs.spring.io/spring-boot/docs/3.1.6/reference/html/howto.html#howto.data-initialization.migration-tool.flyway).

## Schema inventory

The readable initial script is
[`V1__create_greenfield_schema.sql`](../src/main/resources/db/migration/postgresql/V1__create_greenfield_schema.sql).
It contains DDL only, with no credentials, fixture inserts, accounting adoption,
or collection backfill. All six entities extend the mapped `BaseEntity`: each
table has a bigint identity primary key and `created_at` / `updated_at` columns.

| Table | Relationships and constraints |
| --- | --- |
| `app_users` | Required email (unique) and password; role check |
| `parents` | Optional unique user FK; title check |
| `branches` | Optional unique administrator FK; unique RC number, request reference and transaction reference |
| `students` | Parent and branch FKs; required first name, surname and unique registration ID; class, payment type and frequency checks |
| `payments` | Student, parent and branch FKs; unique request/transaction references; category/type/status/frequency checks; lifecycle and accounting fields |
| `payment_collections` | Required payment FK; unique event ID and payment/slot pair; required positive NGN amount; source/type checks and confirmation timestamp |

Foreign keys use PostgreSQL's default restrictive deletion behavior, without
cascading deletes. Unique constraints supply indexes for the one-to-one FKs and
the ledger payment/slot lookup. Additional indexes cover student ownership and
branch, payment ownership and branch, student/category/status, status and provider
identifier. The provider identifier remains nonunique, matching the entity.

Payment amounts and confirmed collections use `numeric(38,2)`, matching the
existing Hibernate BigDecimal mapping and two-decimal calculations. Nullable
fields remain nullable where the entities permit them. SQL enum checks reproduce
the Java enum values; no new payment policy is encoded in this migration.

`payments.accounting_version` is separate from the required optimistic
`row_version`. The former, `billing_anchor` and `virtual_account_expired_at`
remain nullable to preserve the existing legacy/manual-review policy. A migration
does not manufacture their values or infer successful payments. `LocalDateTime`
fields use `timestamp(6) without time zone`; `Instant` fields use `timestamp(6)
with time zone`. Hibernate writes audit timestamps and initial/updated row
versions; there are no database timestamp triggers or fabricated defaults.

## Profile behavior and startup safety

| Profile | Schema owner | Flyway | Hibernate |
| --- | --- | --- | --- |
| `dev` | Disposable in-memory H2 | Disabled | `create-drop` |
| `test` | Isolated in-memory H2 | Disabled | `create-drop` |
| `hosted` | PostgreSQL | Enabled; `classpath:db/migration/postgresql` | `validate` |

Spring Boot's Flyway initializer runs migrations using the application's
datasource before the JPA entity manager is initialized. Hosted configuration
requires migration validation, a present migration location and normal datasource
initialization ordering. SQL initializer scripts are disabled. Configuration
guards reject Hibernate schema creation, disabled Flyway, automatic baselining,
enabled Flyway clean, skipped migration validation and a separate Flyway
connection. Dev/test reject enabling PostgreSQL migrations.

`baseline-on-migrate=false` and `clean-disabled=true` are explicit. A nonempty
schema without Flyway history fails startup. **Do not enable automatic baselining
to make that failure disappear.** Existing databases require a separate verified
schema/data comparison, backup and adoption plan. V1 prioritizes an empty database;
it is not an upgrade script for an arbitrary historical schema.

## Start local PostgreSQL

Use a disposable local instance for verification. PostgreSQL 15 is within the
database versions recognized by this Flyway release. A native PostgreSQL install
also works; choose a dedicated database and role rather than an existing project
database. This task's automated verification uses installed PostgreSQL 18; see
the version limitation below.

For developers with Docker, this PowerShell example generates a transient private
password without printing it and binds PostgreSQL only to loopback:

```powershell
$dbBytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($dbBytes)
$env:POSTGRES_PASSWORD = [Convert]::ToBase64String($dbBytes)
$env:DB_PASSWORD = $env:POSTGRES_PASSWORD
docker run --name greenfield-task9-postgres --detach `
  --publish 127.0.0.1:5432:5432 `
  --env POSTGRES_DB=greenfield `
  --env POSTGRES_USER=greenfield_local `
  --env POSTGRES_PASSWORD postgres:15
docker exec greenfield-task9-postgres pg_isready -U greenfield_local -d greenfield
```

Keep the password in this private shell or a password manager. The Docker
container is disposable and has no persistent volume in this example. If its
name or port is already in use, select a new name/unused loopback port; do not
remove another container or overwrite an existing database. Container environment
values remain visible to someone with local Docker administrative access.
Spring Boot does not automatically load `.env` files.

For a native installation, initialize/start a separate cluster or use the local
PostgreSQL tools to create an empty `greenfield` database owned by a dedicated
role. Set the role password interactively (for example, psql `\password`) rather
than putting a real password in SQL files or shell history. The migration role
needs permission to create the application tables, indexes and constraints in its
target schema. No hosted resource is needed.

## Run the hosted profile locally

In the same PowerShell session, supply the application configuration. With a
native instance, obtain `DB_PASSWORD` privately first instead of using a tracked
file or a literal example password.

```powershell
$env:DB_URL = 'jdbc:postgresql://127.0.0.1:5432/greenfield'
$env:DB_USERNAME = 'greenfield_local'
# DB_PASSWORD is already set privately; it has no application default.
$jwtBytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($jwtBytes)
$env:JWT_SECRET = [Convert]::ToBase64String($jwtBytes)
$env:SPRING_PROFILES_ACTIVE = 'hosted'
mvn spring-boot:run "-Dspring-boot.run.arguments=--server.address=127.0.0.1"
```

JWT configuration requires Base64 encoding of at least 32 random bytes. Keep a
private stable key between runs if existing tokens should remain valid. The
application requires `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` and `JWT_SECRET`;
`DB_URL` must be a PostgreSQL JDBC URL, with any appropriate connection/SSL options.
There are no database or credential fallbacks. No OnePipe settings are required
in hosted mock mode. Automatic account seeding and payment simulation are absent
from hosted mode, so a fresh application has no demo login credentials or
automatically confirmed collections.

Successful startup logs show Flyway applying V1, then JPA entity-manager
initialization. Hibernate only validates. Stop and start the application again
with the same private database settings: Flyway reports the schema is current,
history remains at one applied V1 and existing rows remain intact. Future
migrations will be new `V2__...sql` and later files; never edit an applied V1.

## Inspect history and schema validation

Use psql's password prompt rather than embedding a password in the connection
URI. In another terminal:

```powershell
psql -h 127.0.0.1 -p 5432 -U greenfield_local -d greenfield -W
```

```sql
SELECT installed_rank, version, description, type, script, checksum,
       installed_on, success
FROM flyway_schema_history
ORDER BY installed_rank;

SELECT table_name FROM information_schema.tables
WHERE table_schema = current_schema() ORDER BY table_name;

SELECT column_name, data_type, numeric_precision, numeric_scale, is_nullable
FROM information_schema.columns
WHERE table_schema = current_schema() AND table_name = 'payments'
ORDER BY ordinal_position;

SELECT count(*) AS initially_empty_collections FROM payment_collections;
```

Expect six application tables plus `flyway_schema_history`, one successful version
`1` entry and no inserted collection records on a fresh database. Migrations are
tracked by version and checksum; running startup twice does not rerun V1.
Application startup reaching an open JPA entity manager demonstrates Hibernate
validation against that database. Flyway validation checks migration history and
checksums; it does not replace JPA mapping validation or a manual schema audit.

The automated tests demonstrate missing-column validation failure and checksum
failure in separate generated schemas. Do not damage a real database to reproduce
those checks. Hibernate validation is not a comprehensive verifier of every
index/check constraint; the PostgreSQL catalog and constraint tests cover those
separately.

## Automated verification

Compilation and the ordinary H2 tests require no PostgreSQL:

```powershell
mvn compile
mvn test
```

`PostgreSqlMigrationIntegrationTest` requires actual **loopback PostgreSQL** and
never substitutes H2. Use a disposable local database with schema creation
permission. Each test creates a `task9_<random UUID>` schema and drops only that
owned schema afterward. Never supply production or shared application database
credentials.

```powershell
$env:GREENFIELD_MIGRATION_TEST_DB_URL = $env:DB_URL
$env:GREENFIELD_MIGRATION_TEST_DB_USERNAME = $env:DB_USERNAME
$env:GREENFIELD_MIGRATION_TEST_DB_PASSWORD = $env:DB_PASSWORD
mvn "-Dtest=PostgreSqlMigrationIntegrationTest" test
mvn test
```

Without the migration-test URL, JUnit reports these 14 PostgreSQL tests as skipped;
a passing H2 run alone is not evidence of PostgreSQL compatibility. With the URL,
missing credentials or an inaccessible/nonlocal database fail rather than fall
back. Docker/Testcontainers are not required: native PostgreSQL can run the same
tests. Coverage includes all tables, identity/audit fields, types, eight FKs and
supporting indexes, entity and ledger uniqueness, checks, no legacy evidence
fabrication, migration replay/data preservation, nonempty-schema refusal,
transactional DDL rollback, full hosted startup/restart, schema validation failure
and migration checksum failure.

Integration-test contexts disable automatic timer registration with the test-only
`ManualScheduling` import. This prevents the live expiry job from changing payment
row versions while fixture cleanup deletes rows. Lifecycle tests still invoke the
services directly; a dedicated test verifies automatic registration is removed
and explicit execution remains available. The production scheduler is unchanged.

## Diagnose migration failures

1. Check the active profile and required variable **names**, without posting their
   values. Verify the JDBC URL selects the intended empty database/schema and the
   server is reachable. Authentication, SSL, port and permissions errors must be
   resolved at the local connection level.
2. Read the Flyway startup error and the relevant SQL statement locally. Startup
   must stop on migration errors; do not switch Hibernate to `update` or `create`.
   Avoid sharing logs containing private connection details or application data.
3. A nonempty schema without history needs a separate adoption plan. Do not turn
   on `baselineOnMigrate`, invent history rows, or mark an unknown schema as V1.
4. A checksum mismatch means an applied migration differs from the reviewed
   file/history. Restore the exact applied file and investigate; do not blindly
   run `repair`. Make verified subsequent changes through a new migration.
5. PostgreSQL transactional DDL rolls back the V1 operations on failure. Inspect
   history and actual catalog state before retrying; do not assume partial data
   is safe to delete. The tests verify rollback of a deliberately invalid script
   in their own disposable schema.
6. If Flyway succeeds but Hibernate validation fails, compare the mappings and
   catalog. Do not suppress validation. Back up meaningful data before any
   separately reviewed corrective migration. Flyway `clean` is disabled.

## Remaining limits

- Verification is against local PostgreSQL 18, with Flyway 9.16.3's warning that
  its latest verified PostgreSQL major version is 15. This is actual PostgreSQL
  evidence for this schema/startup, not a claim that the old Flyway release is
  certified for every PostgreSQL 18 feature. A maintained Spring Boot/Flyway
  upgrade and a PostgreSQL version matrix are separate work.
- No existing hosted/legacy database has been inspected, baselined or migrated.
  The initial migration cannot safely adopt an arbitrary nonempty schema.
- No PostgreSQL cluster, role, database, backups, retention policy or hosted
  deployment is provisioned by the application. The local examples are manual.
- Schema constraints preserve the existing model. Audit fields are ORM-managed;
  the collection table has no new append-only database triggers. The reserved
  `VERIFIED_PROVIDER` enum does not enable a trusted provider ingestion path.
- H2 retains its existing independent Hibernate schema and cannot prove that
  PostgreSQL migrations work. No payment calculations, lifecycle behavior,
  authorization policy, frontend API or webhook handling are changed here.
