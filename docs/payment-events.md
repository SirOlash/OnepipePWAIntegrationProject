# Payment collections and trust boundaries

Task 7 implements confirmed **simulated** collections for this internship
prototype. Nothing in the new flow initiates a transfer, debit, OnePipe request
or automatic recurring charge. No real-provider verification capability exists.

## Ledger schema

`PaymentCollection` maps to `payment_collections`, independently of Payment's
planned obligation. Each row contains:

| Field | Meaning / constraint |
| --- | --- |
| id | Generated primary key |
| event_id | Required, globally unique, at most 100 characters |
| payment_id | Required FK to Payment; no cascade deletion of financial evidence |
| transaction_ref | Required invoice reference snapshot, at most 100 characters |
| collection_slot | Required canonical slot, at most 50 characters; unique within payment |
| confirmed_amount | Required positive NGN numeric(38,2), exact planned collection amount |
| currency | Required NGN; database check also rejects other currencies |
| source | MOCK; VERIFIED_PROVIDER reserved for a future verified integration |
| confirmed_at | Required confirmation Instant, normalized to microseconds for H2/PostgreSQL precision |
| collection_type | FULL, DOWN_PAYMENT, INSTALLMENT or SUBSCRIPTION_CYCLE |
| created_at / updated_at | BaseEntity creation/audit timestamps; no update operation is exposed |

Mapped event data is insert-only (`updatable=false`); the application exposes no
ledger edit/delete endpoint. This is application-level append-only behavior, not
a claim that privileged database administrators cannot alter rows. The table has
unique constraints on `event_id` and `(payment_id, collection_slot)`, a foreign key,
NOT NULL checks and a positive-amount/NGN check. Payment adds `accounting_version`
for provenance and `row_version` for optimistic concurrency protection.

## Identities, transactions and concurrency

The currently supported source is always MOCK. The processor has no unverified
provider ingestion method. Both its facade and independent transaction worker
enforce exact dev/mock/opt-in and the authenticated database SUPER_ADMIN role.
There is no HTTP API accepting arbitrary confirmation events or amounts.

The simulation path generates `MOCK-EVENT-<UUID>` on the server and derives the
amount/currency/slot type under the payment lock. `confirmMock` is the guarded
internal entry point for delivering/replaying a synthetic event with its original
ID and timestamp; it is not an HTTP endpoint.

`PaymentEventProcessor` suspends any caller transaction. Its separate Spring
`PaymentCollectionTransaction` bean runs one REQUIRES_NEW transaction that:

1. Checks the environment and database role, validates input and locks the
   payment by its unique transaction reference (`PESSIMISTIC_WRITE`).
2. Resolves the exact planned slot using the unchanged PaymentCalculator and
   invoice monetary snapshot; validates exact amount, currency and identity.
3. Checks persistent event/slot records, provenance and current payment state.
4. Inserts and flushes the collection, derives the balance/counter/state from ledger
   entries, and updates and flushes Payment. Any failure rolls back both writes.

Concurrent events for the same payment serialize on its row lock. Database
uniqueness also protects cross-payment reuse of an event ID. A unique violation
is handled only after the worker transaction has rolled back; the facade makes
one bounded attempt in a fresh transaction to classify replay versus conflict.
No map, synchronized block, process-local idempotency cache or provider call is
used. Optimistic `row_version` rejects stale updates by existing non-ledger writers.
Task 8 cancellation and expiry use compatible payment-row locks; expiry writes
only a separate instruction-expiry marker.

An existing event ID with the same payment/ref, slot, amount, NGN, MOCK source,
collection type and normalized confirmation timestamp is an idempotent replay:
return the recorded event/current accounting without another insert or state
transition, even after settlement or explicit cancellation. Changed details
return a safe 400/409, not an acknowledgment of a new collection. A different
event ID for an already occupied slot returns 409 even if its amount matches.
This avoids accepting an alias event ID without a durable identity record.

Records and uniqueness survive processor/service recreation and, when the
database itself persists, application restarts. The dev/test in-memory H2 database
is intentionally lost when its process/schema is destroyed. Tests recreate the
facade against committed H2 records; no persistent PostgreSQL connection is used.

## Slots and transitions

| Plan | Canonical slots | State after a new confirmed collection | completedPayments |
| --- | --- | --- | --- |
| SINGLE_PAYMENT | FULL | PENDING -> SUCCESSFUL once full amount is confirmed | 1 |
| INSTALLMENT | DOWN_PAYMENT, INSTALLMENT_1 ... INSTALLMENT_n | PENDING -> ACTIVE after any valid collection; SUCCESSFUL only when exact total is confirmed | Recurring installment entries only; excludes DOWN_PAYMENT |
| SUBSCRIPTION | CYCLE_1, CYCLE_2 ... | PENDING -> ACTIVE, then stays ACTIVE | Confirmed cycles |

Due finite installment slots may arrive late or out of order; each is allocated
only once. The separate down payment is immediately eligible. Subscription
cycles must be canonical, sequential and due, with no skipped cycle. Cycle
indices are bounded to nine digits; this is not a provider capability claim.
Due dates use a persisted original anchor, an injected Clock and Africa/Lagos.
No automatic charge runs at those dates. New single-payment FULL confirmations
also require an open, known invoice window. See
[payment lifecycle](payment-lifecycle.md) for boundaries and monthly anchoring.

New confirmations in SUCCESSFUL, CANCELLED or FAILED are rejected. Matching
replays leave their existing state untouched. Individual invalid/failed attempts
do not create confirmed entries, increment counters or terminate a recurring
plan. No failure-notification, refund, chargeback or recovery policy is invented.
Late due slots remain eligible while the arrangement is PENDING/ACTIVE.
Explicit cancellation retains confirmed evidence. Expiry never terminates a
recurring plan or downgrades a settled invoice.

For the NGN 100000.00 / three-installment example, expected slots are 20000.00,
26666.67, 26666.67 and 26666.66. Balances are 80000.00, 53333.33, 26666.66 and
0.00; recurring counters are 0, 1, 2 and 3. Confirmed amounts are not converted to
kobo in the ledger. The earlier provider-request unit conversion remains unchanged.

## Accounting and compatibility

Only newly created `accountingVersion=1` invoices participate. Historical marker-
less rows remain unknown and cannot be silently adopted, regardless of old status
or counters. No historical collections are fabricated. Inconsistent marker-bearing
states without evidence also fail closed.

For managed finite invoices, remaining amount is total minus sum of confirmed
collections, never negative. Exact slot matching rejects partial collections,
overpayments and duplicate allocations. Subscription lifetime remainingAmount
stays null. Historical remainingAmount and confirmedAmount stay null, not zero.

Existing response names remain; `terms.confirmedAmount` is additive. Basis values
are UNPAID, CONFIRMED_COLLECTIONS, UNKNOWN and UNBOUNDED_SUBSCRIPTION. Legacy
REPORTED_SETTLED is no longer proof of a balance. Existing raw historical counters
remain visible but cannot be treated as verified progress. The platform
`totalRevenue` field now sums confirmed ledger amounts (including partial tuition
and subscription cycles), not old successful invoice flags. It represents simulated
confirmed collections in this demo, not real bank revenue.

The frontend was not changed. It must handle unknown/null balances without
substituting zero, retain separate down-payment/progress displays, and clearly
identify simulated collections. Earlier fixed-fee and cent-formatting follow-ups
still apply. Mock invoice query state is independent of the ledger: its process-
local adapter can report Pending after a simulated collection. Queries never
convert that claim into an accounting update.

## Development-only simulation

Default: disabled. In an explicitly selected dev profile with mock mode, set
`DEMO_PAYMENT_SIMULATION_ENABLED=true` or property
`app.demo.payments.simulation.enabled=true`. The controller/service are absent
otherwise, including test, hosted, mixed profiles and real-provider mode. Runtime
checks are repeated by the service/transaction worker so changing configuration
cannot leave a callable unguarded processor. Existing private JWT credentials and
a database SUPER_ADMIN user are required; no key/password is embedded here.

An authenticated super admin can POST `/api/dev/payment-simulations`:

```json
{"transactionRef":"<existing synthetic invoice reference>","collectionSlot":"DOWN_PAYMENT"}
```

The DTO rejects unknown fields, including amount, currency, status, source or
caller-chosen event IDs. Anonymous requests receive 401; parents/branch admins
receive 403; filters, method security and database-role checks enforce access.
Invalid money/slots/JSON return 400, unknown invoices 404 and state/identity
conflicts 409. Results contain `simulated=true`, source MOCK, a synthetic event
ID, slot, confirmed NGN amount, local state, remaining amount and derived count.
Repeating an HTTP slot request generates another event ID and returns 409 for
that occupied slot; the successful collection remains single and unchanged.
Matching original event retries are handled by the internal processor.

## Real webhook and query trust

`POST /api/webhooks/onepipe` always returns generic HTTP 503
WEBHOOK_VERIFICATION_UNAVAILABLE. It does not bind, parse, log or trust body or
Signature headers. Direct WebhookService calls also fail closed. Failed or
unsupported processing is not acknowledged with a 2xx. No OnePipe signature
algorithm is assumed; re-enabling provider events requires authoritative
documentation, a verifier and contract/security tests.

`queryAndFixPaymentStatus` retains its name and original authorized response
fields. It reads a coherent local ledger snapshot, then queries only mock metadata
outside the database transaction. Additive fields expose confirmed/remaining
amounts, balance basis, invoice window and provider availability/consistency.
Lost process-local metadata is unavailable, never regenerated. Raw provider
messages and unrecognized statuses are not trusted. Queries cannot insert
collections, repair counters or alter status. Verified real-provider
reconciliation is still unavailable.

## Database and migration requirements

Dev/test disposable schemas use existing create-drop behavior. Hosted PostgreSQL
stays schema-validation-only, and will require a later reviewed migration before
deployment: create the ledger with the FK/unique/check constraints and add Payment
accounting_version (nullable for legacy rows) and row_version (non-null bigint,
backfilled to 0 for existing rows). Do not mark historical rows version1 or
manufacture ledger records from counters. Hibernate initializes row versions on
new records. Instant storage needs microsecond-capable timestamps and the existing
monetary columns remain numeric(38,2). No migration or hosted schema change is
implemented, and PostgreSQL compatibility is based on mappings, not a live DB test.

## Lifecycle and remaining limits

Task 8 fixes the pending-recurring-plan expiry defect and mock cancellation
rollback divergence. It adds due-date enforcement, anchored billing dates and
read-only ledger/provider diagnostics. [Payment lifecycle](payment-lifecycle.md)
defines the rules and nullable billing_anchor / virtual_account_expired_at
schema additions. No historical backfill or migration is performed.

The mock adapter remains process-local metadata, not a bank ledger. Its Pending
response can disagree with the committed local lifecycle, and is reported
separately. Real webhook verification, trusted external reconciliation, automatic
recurring charges, provider account renewal and production readiness remain
unimplemented.
