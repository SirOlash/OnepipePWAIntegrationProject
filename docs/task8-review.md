# Task 8 independent review

Branch: `fix/secure-demo-config`. Base commit:
`815093f3eb834333ba4f6c2bf015fe2e63e3247d`. The working tree was clean when
Task 8 began. These changes are uncommitted for review. No push, merge, PR state
change, deployment, Render modification, PostgreSQL connection or real-provider
operation was performed.

## Implemented policy

- New single invoices start PENDING and settle only through their exact FULL
  ledger collection. At now >= account expiry, their local payment window is
  closed; financial status stays unchanged. Unknown/malformed expiry rejects
  new FULL simulations with 409. Original recorded events remain replayable.
- Installments start PENDING, become ACTIVE after a partial confirmed collection
  and SUCCESSFUL only at exact settlement. Subscriptions start PENDING, become
  ACTIVE after confirmed cycles and remain ACTIVE between cycles. They have no
  automatic end date or permanent SUCCESSFUL state.
- Expiry records only a separate account-expiry marker. It preserves every
  planned amount, schedule, ledger row, derived counter, balance and provenance;
  it never calls cancelMandate or terminates a recurring arrangement.
- New events in SUCCESSFUL, CANCELLED or FAILED are rejected. No failure,
  recovery, refund or chargeback workflow is invented. Durable matching replays
  are checked before the new-event lifecycle/window/due checks.
- Mock subscription cancellation is an authorized PENDING/ACTIVE -> CANCELLED
  database transaction. Repetition is a successful no-op. Confirmed collections
  remain intact. Unsupported plans, unknown/inconsistent accounting, real mode
  and invalid terminal states return 409.

The [full lifecycle policy](payment-lifecycle.md) explains the boundaries and
remaining limitations. Task 6 monetary calculations and Task 7 ledger identities,
source guards, event/slot uniqueness and atomic rollback remain unchanged.

## Dates, concurrency and reconciliation

An injected Clock supplies lifecycle instants; date interpretation is explicitly
Africa/Lagos. New invoices persist their original billingAnchor. Recurring slot
n is anchor plus n days, weeks or months. January 31 therefore becomes February
28/29 then March 31, without iterative monthly drift. The down payment is a
separate immediately eligible slot. New subscription cycles must be sequential
and due. Installments may be late or out of order once their individual slots
are due. No automatic charge executes at a due date.

Expiry, confirmation and cancellation acquire compatible PESSIMISTIC_WRITE locks
on the same Payment row; rowVersion still protects against stale detached writes.
Cancellation resolves the provider identifier and checks database-backed scope
inside its independent transaction, including when called without HTTP/OSIV.
It never mutates the mock adapter's map. A database rollback cannot leave an
already-mutated provider mandate behind. Concurrent cancellation either precedes
and blocks a new cycle or preserves a cycle that committed before cancellation.

Authorized query reads a coherent local snapshot in a read-only repeatable-read
transaction, then consults mock metadata outside any database transaction.
Existing status/identifier/message fields remain. Additive fields are
confirmedAmount, remainingAmount, balanceBasis, invoiceWindowStatus,
providerAvailability, providerStatus, providerStateConsistent and
localStateConsistent. Amounts come from the ledger; no query repairs state or
creates a collection. Raw adapter messages are not exposed and statuses are
allowlisted. Diagnostic agreement never proves a bank payment.

Pending mock metadata may disagree with an ACTIVE/SUCCESSFUL/CANCELLED local
lifecycle; that is reported separately. Missing metadata after restart is
UNAVAILABLE. It does not cause an invoice, account or event to be recreated.
Historical accountingVersion=null rows remain unknown. Missing billing anchors
are not inferred or backfilled: new recurring confirmations fail closed, while
legitimate recorded events remain replayable.

## Compatibility and schema

No route or existing response field was removed and no frontend code changed.
New due/window conflicts return the standard structured 409 response. Repeated
valid cancellation keeps the existing successful response. Unknown schedule
anchors now produce a null firstBillingDate instead of an inferred date.
Clients should distinguish instruction expiry from plan cancellation and
display unknown/unbounded balances correctly.

Hosted PostgreSQL will require two additional nullable Payment columns:

| Column | Mapping |
| --- | --- |
| billing_anchor | timestamp(6) without time zone, interpreted in Africa/Lagos |
| virtual_account_expired_at | Hibernate Instant-compatible timestamp(6) with time zone |

Task 7's existing ledger, accounting_version and row_version requirements also
remain. No migration or backfill is included, no hosted database was altered,
and PostgreSQL was not tested against a live server. Hosted remains validation
only; dev/test use disposable H2 schemas.

## Changed-file manifest

The review ZIP contains full updated versions of these 30 files, with their
project-relative paths. It is a change bundle to apply to the base commit above,
not a standalone project checkout. It excludes .git, target, local overrides,
environment files, private key material and unrelated files.

| File | Change |
| --- | --- |
| README.md | Replace obsolete lifecycle warnings with current behavior and limitations |
| docs/mock-onepipe.md | Document injected time, persistent lifecycle authority and unavailable metadata |
| docs/payment-events.md | Document due rules, shared locks and read-only diagnostics |
| docs/payment-lifecycle.md | New detailed state, expiry, cancellation, schedule and schema policy |
| docs/payments.md | Update scheduling, subscription, expiry and reconciliation descriptions |
| docs/task8-review.md | Review summary, manifest, validation and remaining limits |
| src/main/java/com/onepipe/config/PaymentTimeConfiguration.java | New payment Clock and explicit application zone |
| src/main/java/com/onepipe/data/entities/Payment.java | Nullable billingAnchor and virtualAccountExpiredAt snapshots |
| src/main/java/com/onepipe/data/repositories/PaymentRepository.java | Shared row-lock lookups by ID/provider ID and expiry ID scan |
| src/main/java/com/onepipe/integration/MockOnepipeClient.java | Inject time for mock invoice creation and expiry |
| src/main/java/com/onepipe/integration/dto/OnePipeQueryResponse.java | Add ledger, window and consistency diagnostics |
| src/main/java/com/onepipe/service/PaymentCollectionTransaction.java | Validate known lifecycle and due/window eligibility after replay checks; injected event time |
| src/main/java/com/onepipe/service/PaymentLifecycleService.java | New independent, locked local cancellation/expiry operations |
| src/main/java/com/onepipe/service/PaymentReconciliationService.java | New safe mock-only metadata diagnostics outside database transactions |
| src/main/java/com/onepipe/service/PaymentReconciliationSnapshot.java | New authorized coherent read-only ledger/lifecycle snapshot |
| src/main/java/com/onepipe/service/PaymentSchedule.java | New anchored recurring dates and strict mock expiry/window policy |
| src/main/java/com/onepipe/service/PaymentService.java | Persist new anchors; delegate expiry, cancellation and query |
| src/test/java/com/onepipe/security/AuthorizationIntegrationTest.java | Preserve authorization cases with managed fixtures and database-authoritative cancellation |
| src/test/java/com/onepipe/security/MockPaymentSimulationIntegrationTest.java | Supply reliable past anchors and valid windows for accepted simulations |
| src/test/java/com/onepipe/security/PaymentReconciliationIntegrationTest.java | New real-filter/JWT H2 read-only and unavailable/inconsistent state coverage |
| src/test/java/com/onepipe/security/PaymentRuntimeFailureIntegrationTest.java | Supply valid single window while preserving rollback/error assertions |
| src/test/java/com/onepipe/service/PaymentBalanceTest.java | Supply new service collaborators |
| src/test/java/com/onepipe/service/PaymentCollectionGuardTest.java | Supply schedule while retaining direct-worker guard tests |
| src/test/java/com/onepipe/service/PaymentCollectionIntegrationTest.java | Supply reliable schedule/window fixtures; retain identity/concurrency/accounting assertions |
| src/test/java/com/onepipe/service/PaymentFinancialIntegrationTest.java | Assert provider dates against the persisted billing anchor |
| src/test/java/com/onepipe/service/PaymentLifecycleIntegrationTest.java | New deterministic H2 lifecycle, cancellation, expiry, rollback, race and due/replay tests |
| src/test/java/com/onepipe/service/PaymentReconciliationServiceTest.java | New sanitized, read-only metadata and historical-state unit tests |
| src/test/java/com/onepipe/service/PaymentScheduleTest.java | New frequency, month/leap-year, zone, due-boundary and expiry tests |
| src/test/java/com/onepipe/service/PaymentServiceMockIntegrationTest.java | Wire real schedule/query collaborators in the isolated context |
| src/test/java/com/onepipe/service/PaymentServiceTransactionReferenceTest.java | Supply new collaborators; preserve distinct-reference assertions |

## Validation

Java 17 / offline Maven compilation succeeded. The final full-suite run rebuilt
95 main sources and 28 test sources and finished BUILD SUCCESS in 3:03.
**642 tests passed; zero failures, errors or skips.** All 546 existing tests
remain, plus 96 new Task 8 invocations:

| New test class | Passing invocations |
| --- | ---: |
| PaymentLifecycleIntegrationTest | 43 |
| PaymentScheduleTest | 30 |
| PaymentReconciliationServiceTest | 14 |
| PaymentReconciliationIntegrationTest | 9 |

The initial `mvn -o -B compile test` also passed. After the final regression
additions, `mvn -o -B test` recompiled and ran the complete suite. Commands used
the existing local Maven dependency cache; no external provider or PostgreSQL
connection was used. Ignored evidence is in target/task8-final-tests.log and
target/surefire-reports; raw logs and generated files are excluded from the ZIP.

Coverage includes inclusive expiry/exclusive open-window boundaries, malformed
and absent timestamps, repeated scheduler runs, actual one-hour mock invoices
across all recurring frequencies, expiry after partial/full confirmation,
pending/active cancellation, repeated cancellation, real-mode rejection,
database rollback, confirmation/cancellation and confirmation/expiry races,
future-cycle rejection and exact due boundaries, historical unknown accounting,
replays after cancellation/expiry or loss of a billing anchor, and late
out-of-order exact installment settlement.

HTTP tests keep Spring Security filters and real signed JWTs. Existing tenant,
parent-only plan-switch, simulation opt-in and disabled-webhook cases remain
passing. New direct-service tests verify owning-parent cancellation and denied
cross-parent cancellation without HTTP/OSIV. Query tests compare database
snapshots before/after and assert metadata is consulted outside a transaction.

Changed files were reviewed for credential/private-key patterns and sensitive
paths; no new real credentials or private configuration were found. The ZIP
contains only the manifest's changed source/documentation files. Compilation and
tests have no unresolved failures. Runtime/provider/hosting limitations below
remain explicit.

## Remaining limits

- Real webhook signature verification and bank reconciliation remain unavailable.
  The public webhook stays rejection-only 503; no OnePipe signature scheme is
  invented or real endpoint contacted.
- Cancellation is locally atomic in mock mode, not a distributed provider commit.
  Real cancellation fails closed. A future verified integration needs reviewed
  durable intent/recovery orchestration. Historical real invoice creation was
  not redesigned or exercised by this task.
- Account renewal/reissue and expired single-invoice resolution are not supplied.
  An expired PENDING school-fee invoice still blocks replacement/plan switching
  under the existing ongoing-obligation rules.
- No automatic debit, failed-charge retry, refund, chargeback, missed-cycle fee
  or future-cycle obligation table exists. Simulation remains exactly dev + mock
  + explicit opt-in + authenticated database SUPER_ADMIN.
- Adapter invoice/merchant metadata remains process-local. Persistent invoice-
  creation idempotency, evidence-based historical repair and PostgreSQL migrations
  remain future work. H2 race tests do not establish production PostgreSQL behavior.
- Frontend fee/cent formatting and clear mock/expired/unknown-state presentation
  remain separate work, as documented in payments.md. No production readiness
  or currently working external payment integration is claimed.
