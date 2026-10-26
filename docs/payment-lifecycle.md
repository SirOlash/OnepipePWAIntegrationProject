# Offline payment lifecycle and reconciliation

These are Greenfield's local demonstration policies. They do not describe an
undocumented OnePipe contract. No transfer, automatic debit, refund or chargeback
is implemented. [Collection identities and security](payment-events.md) and
[exact monetary calculations](payments.md) remain in force.

## Financial state

`PaymentCollection` is the financial source of truth. New invoices have
`accountingVersion=1`, status `PENDING` and zero confirmed collections. Creating
an invoice or receiving adapter metadata does not confirm money.

| Plan | Permitted new collection transition |
| --- | --- |
| Single payment | PENDING -> SUCCESSFUL after its exact FULL collection |
| Installment | PENDING -> ACTIVE after a valid partial collection; ACTIVE -> ACTIVE until exact settlement; then SUCCESSFUL |
| Unlimited subscription | PENDING -> ACTIVE after the first due cycle; ACTIVE -> ACTIVE after subsequent due cycles |

New events in `FAILED`, `CANCELLED` or `SUCCESSFUL` are rejected. There is no
automatic failure/recovery transition. A failed operation rolls back instead of
marking an arrangement failed. Unsupported state/counter/ledger combinations
fail closed. Subscriptions never become permanently successful.

A matching recorded event remains an idempotent replay after settlement,
cancellation, expiry or changes in the current date. Identity checks precede
new-event state/window/due checks. A replay creates no row and changes no status.
An occupied slot with a different event ID remains a conflict.

## Account expiry and invoice windows

The mock adapter creates synthetic, non-transferable account metadata with a
one-hour lifetime. Its `yyyy-MM-dd HH:mm:ss` string is interpreted strictly in
`Africa/Lagos`. This format and lifetime are local mock policy, not provider
semantics. Invalid calendar dates, other formats and missing values are unknown.

The existing 60-second scheduler now visits ledger-managed mock payments under
the payment-row lock. At **now >= expiry**, it records `virtualAccountExpiredAt`
once, using the parsed expiry boundary. This is an expiry marker, not the time
the scheduler happened to observe it. It never changes financial status,
collections, counters, amounts, schedule or accounting provenance. Repeated runs
do nothing further. Missing/malformed expiry is skipped without stopping the
scan. Historical markerless payments and real mode are not adopted by this job.

For a **single payment**, the valid account deadline also closes the local
invoice's payment window. A new FULL simulation is eligible only strictly before
that deadline. At/after the boundary it returns 409 even if the job has not yet
run. Missing/malformed metadata yields an UNKNOWN window and also rejects new
FULL confirmations. Expiry leaves the financial status PENDING, not CANCELLED
or FAILED: the unpaid obligation has not been discharged. A settled invoice
stays SUCCESSFUL and an original event replay still succeeds. The diagnostic
window may report EXPIRED on a settled invoice; it describes the instruction,
not a reversal of settlement. No replacement/renewal endpoint is supplied.
An expired PENDING school-fee invoice consequently still blocks another invoice
or plan switch under the existing ongoing-obligation rules. Renewal/resolution
needs a future reviewed workflow; expiry does not bypass those business rules.

For **installments and subscriptions**, account expiry does not close the
recurring arrangement. Their invoice-window diagnostic is NOT_APPLICABLE.
Future due slots remain eligible for explicit simulation using their persisted
schedule, even when the old account metadata is expired or lost. This does not
authorize real transfers to those account strings. Expiry never calls
`cancelMandate`, fabricates collections or cancels future obligations.

## Clock and anchored schedules

An injectable `Clock` supplies lifecycle decisions and simulated confirmation
timestamps. The application payment zone is `Africa/Lagos` (UTC+01:00). Clock
instants are converted explicitly to this zone; the host operating-system zone
is not used for decisions. Hibernate creation/update audit timestamps retain
their existing mapping and are not used to invent billing dates.

New invoices persist `billingAnchor`, a local date/time in that zone, at invoice
creation. Down payment is a distinct immediately eligible installment slot.
Recurring slot **n** is due at the original anchor plus:

| Frequency | Due date for slot n |
| --- | --- |
| DAILY | n calendar days |
| WEEKLY | n calendar weeks |
| MONTHLY | n calendar months |

The first recurring slot is ordinal 1, one interval later. Monthly calculation
always starts from the original anchor: January 31 -> February 28 (29 in a leap
year) -> March 31. It does not repeatedly add a month to February's clipped day.
The local time is retained. Subscriptions have no automatic end date.

At exactly the due instant a slot is eligible; just before it is not. Eligibility
uses the trusted clock, not a caller's supplied confirmation timestamp. Future
slots return 409. Late collections are allowed. Due installment slots can arrive
out of order, including separately from the down payment; each can be allocated
only once, and finite overpayment remains impossible. Subscription cycles must
be the next sequential cycle as well as due. Nothing executes automatically at
a due date, and no cycle-obligation table or missed-charge penalties are created.
Task 6's 20% down payment, cent distribution and units are unchanged.

Rows created before this task can have no billing anchor. They are not assigned
one from a counter, current time or an assumed schedule. New recurring cycle or
installment confirmations then fail closed pending a reviewed schedule repair;
already recorded events remain replayable. The independent down-payment slot
does not need a recurring date. Read APIs return null firstBillingDate for an
unknown anchor rather than inventing a date.

## Explicit cancellation and concurrency

The existing subscription-cancel endpoint keeps Task 5's database-backed resource
scope: owning parent, own-branch administrator or super administrator. It locks
the payment in a fresh transaction and checks scope against the locked
row. Only mock, ledger-managed subscriptions with consistent known accounting
can move PENDING/ACTIVE -> CANCELLED. Repeating a valid cancellation is a
successful no-op. Single payments, installments, FAILED/SUCCESSFUL states and
unknown/inconsistent accounting return 409. Legacy records are not converted
to managed accounting in order to cancel them.

Database lifecycle is authoritative in this offline mode. The operation does
not call the adapter or mutate its map. A database failure therefore leaves
both the committed lifecycle and adapter state unchanged. Confirmed collections
are retained. Adapter Pending after local cancellation is expected diagnostic
disagreement, not an instruction to revive a subscription.

Confirmation, cancellation and expiry use PESSIMISTIC_WRITE locks on the same
Payment row. If cancellation wins, a new collection is rejected; if a due cycle
wins, its collection commits before cancellation and is preserved. Expiry can
record its separate marker without overwriting either result. Optimistic
`rowVersion` still rejects stale detached writes. Transactions never make a
provider call during these lifecycle operations.

Real-provider cancellation is unsupported and fails closed. A future verified
provider flow would require a reviewed durable intent/outbox/saga and recovery
design; no atomic database-plus-provider commit is claimed. The historical real
invoice-creation adapter is not redesigned or exercised by this task.

## Read-only reconciliation and restart

The authorized `/api/payments/{onePipePaymentId}/query` endpoint retains its
original identifiers, local `status` and explanatory `message`. Additive fields
report confirmedAmount, remainingAmount, balanceBasis, invoiceWindowStatus and
provider diagnostics, plus nullable localStateConsistent for ledger/status/counter
agreement. Unknown historical evidence remains unknown; contradictions are
reported without repair. Its local snapshot is read in a separate read-only,
repeatable-read transaction. Only scalar data leaves that transaction. Mock
metadata is consulted afterward, with no open database transaction; real mode
does not contact a provider through this endpoint.

Amounts come from committed collections, not adapter claims, counters, webhook
payloads or expiry. The query makes no accounting or lifecycle repair writes.
Adapter status is allowlisted and its raw message is never copied into the
response. Pending metadata after an ACTIVE/SUCCESSFUL/CANCELLED local state is
reported as disagreement. Diagnostic agreement never proves a bank collection.
The snapshot is an observation, not a guarantee that the state cannot change
after the response was read.

The mock adapter's maps remain process-local invoice metadata. It never changes
to Successful because of ledger simulation. After restart or loss of a map
entry, providerAvailability is UNAVAILABLE and provider consistency is unknown;
no replacement invoice, account, ID or ledger event is invented. If PostgreSQL
persists, its committed ledger/lifecycle remains authoritative. Disposable H2
dev/test data still disappears when its database is destroyed.

Historical accountingVersion=null rows keep unknown confirmed/remaining amounts
regardless of old statuses/counters; subscription finite balance remains null.
They can be read and diagnosed but are not silently migrated, settled or
cancelled by the new managed lifecycle. Evidence-based repair is future work.

## Compatibility, schema and remaining work

No route or existing response field was removed. Query fields are additive.
Due/window conflicts now return safe 409 responses; repeated valid cancellation
returns the existing success response. Clients should distinguish an expired
payment instruction from a cancelled plan and show unknown balances as unknown.
No frontend files were changed.

The fresh-database Flyway V1 migration includes Task 7's ledger/provenance/version
requirements and nullable Payment columns `billing_anchor` (timestamp(6) without
time zone, interpreted in Africa/Lagos) and `virtual_account_expired_at`
(Hibernate Instant-compatible timestamp(6) with time zone). Flyway initializes
an empty PostgreSQL database before Hibernate validates the schema; Hibernate
does not create or update hosted tables. Dev/test retain disposable Hibernate H2
schemas with Flyway disabled.

No historical anchor, expiry marker or accounting evidence is inferred or
backfilled. Automatic baselining is disabled, and an existing nonempty database
requires a separately verified migration/adoption plan. See
[database migration setup and verification](database-migrations.md).

Real signature verification, trusted bank reconciliation, account renewal,
failure/retry policy, automatic debits, persistent invoice-creation idempotency,
refunds/chargebacks and production operations remain unimplemented. The public
webhook remains a rejection-only 503 route. Simulation still requires exactly
dev + mock + explicit opt-in + database SUPER_ADMIN. This is a portfolio
demonstration, not a production payment system.
