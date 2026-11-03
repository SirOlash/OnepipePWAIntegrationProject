# Task 7 independent review

Branch: `fix/secure-demo-config`. Starting commit:
`5387150057b620cd3739f78543604c464eede1b6`. The initial working tree was clean.
All Task 7 work is uncommitted; no push, merge, PR update, deployment, Render
change, PostgreSQL connection or real-provider transaction was performed.

## Outcome and schema

The new PaymentCollection ledger stores confirmed simulated collections separately
from planned Payment obligations. Fields: generated id, unique event_id,
payment_id FK, transaction_ref, collection_slot, exact numeric(38,2) NGN
confirmed_amount, currency, source, confirmed_at and collection_type, plus
BaseEntity creation/audit timestamps. Database constraints enforce required
fields, positive amounts, NGN currency, a unique event and a unique payment/slot.
No ledger edit/delete API exists. VERIFIED_PROVIDER is reserved and has no
enabled ingestion path.

Payment adds nullable accounting_version and optimistic row_version. New invoices
are explicitly version1 for accounting; historical records remain unmarked and
UNKNOWN regardless of old counters or successful flags. No backfill is invented.

## Transaction, concurrency and state

The facade suspends outer transactions; its separate worker executes one
REQUIRES_NEW transaction, locks Payment by unique transaction reference using
PESSIMISTIC_WRITE, validates identity/planned amount/currency/state/provenance,
inserts the collection and derives Payment state/counter from committed ledger
evidence plus this transaction's insertion. A failed insert or settlement update
rolls back everything. Global event-ID races are handled after rollback with one
bounded classification attempt in a fresh transaction. Database uniqueness is
the final guard, not a process-local map.

Matching original events are no-ops on retry, including after settlement or
cancellation. Conflicting reuse fails. A different event ID for an occupied slot
returns409 even with the same amount; no undurable alias is accepted. Persistence
is verified through a recreated facade against the same committed H2 records.

Single FULL collection -> SUCCESSFUL. Finite installments -> ACTIVE while partial,
SUCCESSFUL only after exact full settlement. Installment collections may arrive
out of order. Subscription CYCLE_n identities are sequential and unique; confirmed
cycles leave the unlimited plan ACTIVE. New events cannot reactivate CANCELLED,
FAILED or SUCCESSFUL. Matching replays never change state. Invalid/failed attempts
never become confirmed collections or terminate an entire recurring plan.

The NGN100000 / three-installment case uses 20000.00, 26666.67, 26666.67,
26666.66. Remaining balances become 80000.00, 53333.33, 26666.66, 0.00. Counters
for installments exclude the initial down payment and become 0,1,2,3. Single
completedPayments=1 after full collection; subscriptions count confirmed cycles.
There are no independent collection-counter increments.

Managed finite balances equal invoice total minus ledger sum, including known
zero-collection cancelled/failed invoices. Historical and inconsistent unsupported
states remain unknown. Lifetime subscription balances stay null. Overpayment is
rejected. Existing DTO fields remain; terms.confirmedAmount is additive. The
platform totalRevenue field now sums confirmed ledger entries, representing
simulated confirmed amounts rather than real bank revenue.

## Trust and API implications

The public OnePipe webhook always returns generic503, without parsing/logging raw
body or Signature headers. Direct WebhookService calls also fail closed. No
signature specification or verifier is invented. Authorized provider queries
remain available but read-only; a provider success/failure claim cannot insert a
collection or mutate status/counters.

POST `/api/dev/payment-simulations` is disabled by default. It requires exactly
dev profile, mock mode, explicit `DEMO_PAYMENT_SIMULATION_ENABLED=true`, an
authenticated SUPER_ADMIN and the database role check. Controller registration,
HTTP/method authorization and service/worker runtime guards all apply. It is
unavailable in test, hosted and real mode, including direct worker calls.

Only transactionRef and collectionSlot are accepted. The server supplies the
synthetic event ID, expected NGN amount, currency/type and confirmation timestamp.
Unknown fields, including amount and provider claims, are rejected. Results are
explicitly simulated. No provider method is called by collection confirmation.

The final error-handling correction preserves explicit 400/401/403/404/409
handlers. Unexpected runtime failures now return generic HTTP500 using the
standard code/message/status/timestamp/path structure. The path is the mapped
route template (or `/` when unavailable), not a concrete URI containing an
invoice/payment identifier. Exception messages, SQL, causes and traces are not
included. A regression injects an unexpected failure after both ledger and payment
writes have flushed, proving the entire confirmation still rolls back before the
HTTP500 response is produced. No ledger, simulation or webhook rule changed.

No frontend code changed. Clients must preserve null/unknown balances, display
simulated collections clearly and distinguish the installment down payment from
recurring progress. Historical `REPORTED_SETTLED` is no longer used as proof.
Existing mock invoice query maps remain independent of the collection ledger;
querying an invoice can still say Pending after a local simulated settlement.

## Schema and Task 8 boundaries

No migrations or hosted schema updates are implemented. Future migrations must
create the ledger constraints and add accounting_version (null on historical
rows) and non-null row_version (backfill0), before hosted schema validation can
succeed. Dev/test schemas remain disposable H2; PostgreSQL compatibility follows
JPA mappings and has not been tested against an external database.

The known pending recurring-plan account-expiry defect remains for high-priority
Task 8. Optimistic versions reject stale saves after confirmation but do not
repair expiry policy, due dates, late-event recovery or cancellation/provider
reconciliation. A mock mandate cancellation can precede a database optimistic
conflict; Task 8 must address that consistency. There is no automatic recurring
charge, refund/chargeback policy or authoritative real-webhook verifier.

## Complete changed-file inventory

N=new, M=modified; paths are project-relative. This report is included.

| File | Change and reason |
| --- | --- |
| `README.md` | M: current ledger, simulation, fail-closed webhook and query behavior. |
| `application-example.properties` | M: document disabled-by-default simulation setting. |
| `docs/authorization.md` | M: super-admin simulation access and closed unverified financial path. |
| `docs/configuration.md` | M: simulation opt-in and hosted schema prerequisites. |
| `docs/mock-onepipe.md` | M: distinguish mock invoice maps from persistent simulated collections. |
| `docs/payments.md` | M: replace legacy inferred balances with ledger evidence; retain Task8 warning. |
| `docs/payment-events.md` | N: detailed ledger, transaction, trust, state and schema specification. |
| `docs/task7-review.md` | N: complete change inventory and independent-review report. |
| `src/main/java/com/onepipe/config/MockPaymentSimulationCondition.java` | N: exact dev/mock/explicit-opt-in condition, shared by runtime guards. |
| `src/main/java/com/onepipe/config/SecurityConfig.java` | M: super-admin-only simulation route. |
| `src/main/java/com/onepipe/controllers/MockPaymentSimulationController.java` | N: guarded simulation request/response surface. |
| `src/main/java/com/onepipe/controllers/WebhookController.java` | M: generic503, no payload parsing or sensitive logging. |
| `src/main/java/com/onepipe/data/entities/Payment.java` | M: accounting provenance and optimistic row version. |
| `src/main/java/com/onepipe/data/entities/PaymentCollection.java` | N: persistent immutable-mapped collection data and DB constraints. |
| `src/main/java/com/onepipe/data/enums/CollectionSource.java` | N: MOCK and reserved future verified-provider source. |
| `src/main/java/com/onepipe/data/enums/CollectionType.java` | N: explicit full/down/installment/cycle collection types. |
| `src/main/java/com/onepipe/data/repositories/PaymentCollectionRepository.java` | N: event, slot and payment ledger lookups. |
| `src/main/java/com/onepipe/data/repositories/PaymentRepository.java` | M: payment row lock and ledger-based totalRevenue query. |
| `src/main/java/com/onepipe/dtos/request/MockPaymentSimulationRequest.java` | N: accept invoice/slot only and reject unknown claims. |
| `src/main/java/com/onepipe/dtos/response/PaymentCollectionResult.java` | N: explicitly simulated confirmation result. |
| `src/main/java/com/onepipe/dtos/response/PaymentTerms.java` | M: additive confirmedAmount field. |
| `src/main/java/com/onepipe/exceptions/GlobalExceptionHandler.java` | M: safe409 for concurrent changes and generic500 fallback without exception/URI identifier disclosure. |
| `src/main/java/com/onepipe/service/MockPaymentSimulationService.java` | N: database-role/runtime checks before event processing. |
| `src/main/java/com/onepipe/service/PaymentAccounting.java` | N: ledger sum, exact remaining balance and derived counters. |
| `src/main/java/com/onepipe/service/PaymentCollectionGuard.java` | N: runtime environment/role protection even for direct worker calls. |
| `src/main/java/com/onepipe/service/PaymentCollectionTransaction.java` | N: atomic locked confirmation, slot validation, replay and derived transitions. |
| `src/main/java/com/onepipe/service/PaymentEventProcessor.java` | N: facade with rollback-safe uniqueness-conflict classification. |
| `src/main/java/com/onepipe/service/PaymentService.java` | M: mark new invoices, expose ledger balances/terms, make queries read-only. |
| `src/main/java/com/onepipe/service/WebhookService.java` | M: remove all unsigned state/counter updates and fail closed directly. |
| `src/main/resources/application.properties` | M: simulation defaults off through an environment-backed setting. |
| `src/test/java/com/onepipe/config/MockPaymentSimulationConfigurationTest.java` | N: profile/mode/opt-in availability and runtime changes. |
| `src/test/java/com/onepipe/exceptions/GlobalExceptionHandlerTest.java` | N: safe500 responses, route-template privacy and preserved explicit error statuses. |
| `src/test/java/com/onepipe/security/MockPaymentSimulationIntegrationTest.java` | N: actual JWT/security/H2 tests, roles, invalid claims and no provider calls. |
| `src/test/java/com/onepipe/security/PaymentRuntimeFailureIntegrationTest.java` | N: generic500 after flushed writes rolls back completely; expected400/409 have no financial side effects. |
| `src/test/java/com/onepipe/security/WebhookSecurityIntegrationTest.java` | N: rejected webhook, no raw logs, query claims never mutate financial state. |
| `src/test/java/com/onepipe/service/PaymentBalanceTest.java` | M: ledger-backed progress and unknown historical/inconsistent states. |
| `src/test/java/com/onepipe/service/PaymentCollectionGuardTest.java` | N: direct worker environment/role protection and opt-in removal. |
| `src/test/java/com/onepipe/service/PaymentCollectionIntegrationTest.java` | N: exact amounts, persistence/replay, conflicts, concurrency, rollback, stale updates and DB constraints. |
| `src/test/java/com/onepipe/service/PaymentServiceMockIntegrationTest.java` | M: wire accounting dependency while retaining provider isolation assertions. |
| `src/test/java/com/onepipe/service/PaymentServiceTransactionReferenceTest.java` | M: wire accounting while preserving reference uniqueness checks. |

## Verification

Final offline Maven test run: **BUILD SUCCESS; 546 tests passed, zero failures,
errors or skipped tests**. Maven compilation succeeded for the 90 application
sources and 24 test sources. This adds 146 cases over the Task 6 baseline of 400. The existing
unchecked-operation compiler warning in unchanged RealOnePipeClient remains;
there are no compilation errors.

All existing authorization, financial-calculation, provider, reference, seeding
and profile suites pass. HTTP integration tests retain actual Spring Security
filters and privately generated test JWTs. Database tests use isolated H2;
provider interactions are mocked/offline, never OnePipe or PostgreSQL.

| Test class | Passing cases |
| --- | ---: |
| DataSeederIntegrationTest | 22 |
| DataSeederTest | 29 |
| EnvironmentConfigurationTest | 69 |
| MockPaymentSimulationConfigurationTest | 13 |
| SchoolFeeScheduleTest | 13 |
| TestControllerTest | 5 |
| GlobalExceptionHandlerTest | 14 |
| DevApplicationStartupTest | 1 |
| MockApplicationStartupTest | 1 |
| MockOnepipeClientTest | 16 |
| OnepipeProviderSelectionTest | 13 |
| RealOnePipeClientAuthTest | 6 |
| AuthorizationIntegrationTest | 89 |
| MockPaymentSimulationIntegrationTest | 17 |
| PaymentRuntimeFailureIntegrationTest | 3 |
| ResourceAuthorizationTest | 6 |
| WebhookSecurityIntegrationTest | 36 |
| PaymentBalanceTest | 25 |
| PaymentCalculatorTest | 34 |
| PaymentCollectionGuardTest | 5 |
| PaymentCollectionIntegrationTest | 52 |
| PaymentFinancialIntegrationTest | 71 |
| PaymentServiceMockIntegrationTest | 3 |
| PaymentServiceTransactionReferenceTest | 3 |
| **Total** | **546** |

The original focused run passed145 cases and the original final Task 7 run passed
529. The final error-handling correction adds 17 passing regressions; its focused
run passed17 and its complete run passed546. These verify explicit error status
preservation, no original runtime-message/identifier/SQL/trace disclosure, and
atomic rollback after payment and collection writes were flushed. Diff whitespace
checks and an exact comparison of this 40-file inventory to working-tree changes
passed. The installment calculator,
demo seeder, database profile files, profile validator and real-provider adapter
were not modified. The expiry method's policy remains unchanged.

Stop for independent review. No commit, push, merge, deployment or Task8
implementation is authorized by this task.
