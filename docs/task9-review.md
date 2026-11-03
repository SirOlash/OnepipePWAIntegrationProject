# Task 9 review: PostgreSQL database migrations

Implementation is uncommitted on `fix/secure-demo-config`, based on
`4d63a1fbd3bbc566be95867eee08a54fc75b8afe`. No commit, push, PR change, deployment,
Render operation or external database change was performed.

## Result

Flyway 9.16.3, managed by Spring Boot 3.1.6, creates the complete schema on empty
PostgreSQL before JPA initialization. Hosted Hibernate remains `validate`.
Migration/checksum/schema failures stop startup. Automatic baselining and Flyway
clean are disabled. Dev/test retain in-memory H2 and Hibernate `create-drop`, with
Flyway disabled. No payment business logic, entity mapping, ledger design,
authentication/authorization behavior, webhook handling or frontend was changed.

V1 covers all six entities and eight foreign keys, bigint identity generation,
audit timestamps, mapped enum/unique/not-null constraints, supporting indexes,
`numeric(38,2)` monetary values, collection event/slot uniqueness and positive NGN
checks, accounting version, optimistic row version, billing anchor and account
expiry marker. It has no data inserts or historical accounting backfill.

## Every changed file

The review ZIP contains these **31 files**, preserving project-relative paths.
Six are new; the other 25 are modifications. There are no deleted files.

| File | Reason |
| --- | --- |
| `.dockerignore` | Allow the nested PostgreSQL SQL migration into the Docker build context while excluding unrelated dumps |
| `.gitignore` | Allow that migration in version control while retaining local SQL dump exclusions |
| `pom.xml` | Add the Boot-managed Flyway core dependency |
| `src/main/java/com/onepipe/config/EnvironmentConfiguration.java` | Enforce safe per-profile migration settings and prevent a separate Flyway datasource |
| `src/main/resources/application.properties` | Shared explicit migration safety/ordering settings; Flyway off unless hosted |
| `src/main/resources/application-hosted.properties` | Enable the PostgreSQL migration location and disable SQL initialization; retain Hibernate validation |
| `src/main/resources/db/migration/postgresql/V1__create_greenfield_schema.sql` (new) | Complete initial PostgreSQL DDL without seed data or legacy adoption |
| `application-example.properties` | Document the migration properties without credentials |
| `README.md` | Correct current hosted schema/startup/testing instructions |
| `docs/configuration.md` | Explain profiles, migration initialization and test scheduling isolation |
| `docs/mock-onepipe.md` | Correct the obsolete empty-database blocker |
| `docs/payment-events.md` | Record the implemented fresh-schema ledger/version constraints and adoption limits |
| `docs/payment-lifecycle.md` | Record the implemented nullable lifecycle schema fields and no-backfill policy |
| `docs/payments.md` | Link the matching PostgreSQL monetary precision |
| `docs/database-migrations.md` (new) | Local PostgreSQL/JWT setup, inspection, failure diagnosis, tests and limitations |
| `docs/task9-review.md` (new) | This manifest and validation report |
| `src/test/java/com/onepipe/config/EnvironmentConfigurationTest.java` | Migration guard and profile regressions, including secret-safe failure messages |
| `src/test/java/com/onepipe/migration/PostgreSqlMigrationIntegrationTest.java` (new) | Fourteen actual PostgreSQL migration/constraint/hosted-startup regressions |
| `src/test/java/com/onepipe/testsupport/ManualScheduling.java` (new) | Test-only removal of automatic timer registration to prevent cleanup races |
| `src/test/java/com/onepipe/testsupport/ManualSchedulingTest.java` (new) | Prove timer isolation, explicit invocation and normal production-style registration |
| `src/test/java/com/onepipe/config/DataSeederIntegrationTest.java` | Import test-only scheduling isolation |
| `src/test/java/com/onepipe/integration/DevApplicationStartupTest.java` | Import test-only scheduling isolation |
| `src/test/java/com/onepipe/integration/MockApplicationStartupTest.java` | Import test-only scheduling isolation |
| `src/test/java/com/onepipe/security/AuthorizationIntegrationTest.java` | Import test-only scheduling isolation |
| `src/test/java/com/onepipe/security/MockPaymentSimulationIntegrationTest.java` | Import test-only scheduling isolation |
| `src/test/java/com/onepipe/security/PaymentReconciliationIntegrationTest.java` | Import test-only scheduling isolation |
| `src/test/java/com/onepipe/security/PaymentRuntimeFailureIntegrationTest.java` | Import test-only scheduling isolation |
| `src/test/java/com/onepipe/security/WebhookSecurityIntegrationTest.java` | Import test-only scheduling isolation |
| `src/test/java/com/onepipe/service/PaymentCollectionIntegrationTest.java` | Import test-only scheduling isolation |
| `src/test/java/com/onepipe/service/PaymentFinancialIntegrationTest.java` | Import test-only scheduling isolation |
| `src/test/java/com/onepipe/service/PaymentLifecycleIntegrationTest.java` | Import test-only scheduling isolation |

## Compilation and test evidence

Validation used Java 17.0.17, Maven 3.9.11 and an isolated native PostgreSQL
**18.3** cluster bound to loopback on an unused port. Docker was unavailable, so
no Testcontainers dependency or Docker build was used. Ephemeral test credentials
and database files were confined to ignored `target/`, excluded from the ZIP.

| Check | Result |
| --- | --- |
| Maven `compile` | BUILD SUCCESS |
| Focused migration/configuration/scheduling tests | 108 passed, 0 failed/errors/skipped |
| Complete Maven `test` suite | **681 passed, 0 failures, 0 errors, 0 skipped**, 30 test classes |
| Actual PostgreSQL migration tests within the full suite | 14 passed; no H2 substitution |
| Full-suite duration | 3 minutes 51 seconds |
| Migration-test schema cleanup | Zero owned `task9_<UUID>` schemas remained |
| Maven migration resource | Copied classpath SQL matches reviewed source byte for byte |
| Diff whitespace check | Passed |

The complete suite was run once after the implementation and passed; there was
no failing full-suite run hidden by retries. The additional 39 tests comprise
23 configuration cases, 14 PostgreSQL cases and two scheduling cases, alongside
the previous 642 tests. Later changes were documentation and the narrow Docker
ignore exception; application/test code did not change after that passing run.

### Cleanup optimistic-lock investigation

The earlier Task 8 precommit log identified an optimistic-lock failure deleting
`Payment` during lifecycle fixture cleanup. The real `@EnableScheduling` timer
can update account-expiry markers and the optimistic row version while cleanup
has already read rows for deletion. Merely setting Boot's task scheduling flag
does not remove the processor registered explicitly by `@EnableScheduling`.

Full application integration tests now import `ManualScheduling`, which removes
automatic scheduled-method registration only in test contexts. Production
scheduling and explicit lifecycle execution remain unchanged. Tests retain their
existing cleanup and transaction/concurrency assertions; no retry, exception
suppression, weakened row version or arbitrary sleep was added. The full passing
log contained no optimistic-lock cleanup exception.

## Manual verification

Follow [the complete PostgreSQL guide](database-migrations.md) to:

1. Start a dedicated local native PostgreSQL instance or a loopback-only Docker
   PostgreSQL 15 container with a privately generated password.
2. Set `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` and a private Base64 `JWT_SECRET`.
3. Run `mvn spring-boot:run` with `SPRING_PROFILES_ACTIVE=hosted` and local HTTP
   binding. Verify V1 precedes successful JPA entity-manager initialization.
4. Inspect the six tables and successful V1 in `flyway_schema_history` using psql.
5. Restart with the same database: V1 is not rerun and existing rows are preserved.
6. Set the three `GREENFIELD_MIGRATION_TEST_DB_*` variables to a disposable local
   database and run the PostgreSQL tests or the entire Maven suite.

The guide includes SQL history/catalog queries and failure diagnosis. Hosted
mode supplies no automatic demo login or collection data. Migration tests use
separate generated schemas for deliberate checksum, invalid SQL and missing
column failures; never perform those destructive checks on an application DB.

## Remaining limitations

- Flyway 9.16.3 warns that its latest verified PostgreSQL major version is 15,
  while the available local server was 18.3. All actual PostgreSQL tests passed;
  this does not certify every PostgreSQL 18 feature. PostgreSQL 15 and a broader
  version matrix were not run here. A maintained dependency upgrade is separate.
- No existing nonempty or hosted database was adopted. Baseline/upgrade planning,
  backups and any evidence-based repair require separate verification.
- Without `GREENFIELD_MIGRATION_TEST_DB_URL`, the 14 PostgreSQL tests are explicitly
  skipped; ordinary H2 success must not be reported as PostgreSQL compatibility.
- Hibernate validation does not check every index/check constraint. PostgreSQL
  catalog and constraint tests cover those separately. ORM audit fields do not
  imply new database triggers or a general append-only enforcement mechanism.
- No real provider verification, bank reconciliation or hosted deployment was
  introduced. The current mock and frontend contracts are preserved.

The changed-files ZIP excludes `.git`, build/test artifacts, private environment
files, local database contents and credentials. Changes await independent review.
