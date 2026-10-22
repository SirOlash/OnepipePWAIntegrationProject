# Payment calculations and validation

Greenfield remains an internship prototype. These rules describe planned
obligations in offline mock mode, not verified money movement. Invoice creation
leaves status `PENDING` and `completedPayments=0`. No success endpoint, simulated
collection, webhook signature scheme or durable event idempotency is added.

## Units and limits

All domain, database and frontend API amounts are `BigDecimal` Nigerian naira
(NGN), normalized to two decimal places. Submitted amounts must be positive and
have at most two decimal places; even `100.000` is rejected rather than rounded.
The existing Hibernate monetary mapping is numeric(38,2); inputs are limited to
36 integer digits and two fractional digits. No schema migration is introduced.
Only provider request construction converts NGN to integral kobo, exactly once:
`100000.00 NGN -> 10000000 kobo`. Neither adapter multiplies again. Calculations
use no floating-point arithmetic.

## Server-authoritative fees

[school-fees.properties](../src/main/resources/school-fees.properties) is the
single default fee schedule. Its JSS1/JSS2/JSS3/SS1/SS2/SS3 sample prices are
100000.00 / 110000.00 / 120000.00 / 130000.00 / 140000.00 / 150000.00 NGN.
These are synthetic demonstration prices, not real school fees or provider
sandbox requirements. ClassGrade now contains class identifiers only.

Override individual fees through Spring properties (for example
`school.fees.JSS1=123456.78`) or environment variables (`SCHOOL_FEES_JSS1`).
Every configured fee is validated at startup. All profiles use the same fee
source; no database or profile settings are changed. Changing a fee affects new
invoices only. Existing invoice amounts, down payments and counts are snapshots.

Registration and subsequent `SCHOOL_FEES` invoices use that student's class fee.
Their optional submitted `amount` must equal the server fee; conflicts return
400. An ongoing school-fee invoice remains a 409 business-state conflict.
School-fee type/frequency/count must match the saved student plan; invoice
creation cannot bypass the authenticated owning parent's plan-switch policy.
Legitimate `ADHOC_PAYMENT` amounts remain caller-supplied, positive NGN values.

## Single payment

The full invoice amount is due. No down payment, frequency or installment count
is stored or sent. Optional irrelevant schedule inputs are not used. Creating
the invoice does not settle it. A finite invoice already recorded as
`SUCCESSFUL` reports zero, explicitly labeled `REPORTED_SETTLED`; this is a
legacy state assertion, not independent evidence of a verified collection.

## Installments

The planned down payment is 20% of the invoice total, rounded to two decimals
using `HALF_UP`. The rest is divided in integer kobo: divide by the selected
count, then allocate one extra kobo to the earliest installments until the
remainder is exhausted. Installments differ by at most one kobo and sum with
the down payment to exactly the invoice total. For example:

| Component | NGN |
| --- | ---: |
| Invoice | 100000.00 |
| Planned down payment | 20000.00 |
| Installment 1 | 26666.67 |
| Installment 2 | 26666.67 |
| Installment 3 | 26666.66 |

`numberOfInstallments` in requests and `numberOfPayments` on the invoice mean
the recurring installments **after** the initial down payment: three means
four planned collection events. The down payment is not a completed installment.
`amountPerCycle` is the first scheduled installment, not a promise that every
installment has that identical amount. `terms.installmentAmounts` is authoritative
for the full local planned schedule, including the final rounding adjustment.

The selected `DAILY`, `WEEKLY` or `MONTHLY` frequency is required. Count must be
1 through 10000; the upper bound limits API schedule size, not OnePipe capability.
Every collection, including the rounded down payment, must be at least NGN 0.01.
Amounts/counts producing zero collections are rejected. No default count or
frequency is substituted for missing/invalid data. Recurring ad-hoc requests may
supply frequency/count or reuse a complete valid saved student configuration.

First recurring billing is one selected interval after invoice creation, using
the invoice creation timestamp. Calendar months use Java calendar arithmetic
(January 31 + one month becomes the last valid day of February). Times retain
the application's existing local `LocalDateTime` convention; no provider time
zone contract is claimed. Later event execution and recurring date reconciliation
are outside this task.

The existing OnePipe request can represent total/down/count/frequency/start date,
but has no documented field here for a variable final installment. No new
provider fields are invented. Exact local scheduling is tested; real-provider
rounding or variable collection support remains unverified. Mock mode sends no
requests and executes no collections.

## Unlimited subscriptions

The approved model treats the invoice amount as the amount **per billing cycle**.
For a school-fee subscription this means the configured class fee on every cycle,
not repayment of one finite tuition bill. ADHOC subscriptions use their specified
cycle amount. Choose this plan deliberately; a finite obligation uses installments.

Frequency is required. Planned first billing is one selected interval after
invoice creation. The planned terms have no down payment, automatic end date or
finite installment count. An irrelevant installment count is not used or stored.
Virtual-account expiry must not imply subscription termination, but the current
expiry scheduler can still cancel the entire pending subscription. This is an
unresolved lifecycle defect, not a subscription end date; see the high-priority
Task 8 requirement below. Explicit cancellation retains the existing
authenticated route and state handling.

There is no persisted cycle-obligation or confirmed-payment ledger. Therefore an
unlimited subscription has no aggregate finite `remainingAmount`; it returns null
with `terms.balanceBasis=UNBOUNDED_SUBSCRIPTION`. The existing `totalAmount` and
API `amount` fields denote the per-cycle charge for this plan, not lifetime debt.
Future per-cycle balances require the Task 7 model below.

## Balances and Task 7 accounting boundary

New unpaid finite invoices report their full invoice amount. Merely planning a
down payment or an installment never reduces it. Recorded settled finite invoices
report 0.00. Other non-pending invoices, or unsettled invoices with nonzero legacy
collection counters, return null with `balanceBasis=UNKNOWN`. Existing counters
cannot prove which amounts were collected and may include a down payment or
duplicate notifications; they are returned unchanged for compatibility, not
used for balance arithmetic or certified completion percentages. Null is not zero.

Accurate partial balances cannot be implemented from this Payment entity alone.
Task 7 should persist verified collection events with a unique provider/event
identity, invoice relationship, exact NGN/kobo units and currency, amount,
confirmation time, and down-payment/installment/cycle allocation. Deduplicate and
validate events before atomically recording them and deriving paid total, remaining
balance and recurring-installment count. Settled amounts should cap at the finite
obligation (and identify overpayment separately). Model subscription cycle
obligations separately. Do not backfill events from existing counters.

WebhookController, WebhookService, reconciliation and expiry state transitions
remain unchanged. Their security, replay and state-ordering limitations persist.
Recorded SUCCESSFUL is not proof while that work is pending. No partial collection
is fabricated here, and no test triggers a real transfer or public success route.

## Task 8: Payment Lifecycle and Reconciliation

**High priority: verified limitation; not corrected in Task 6.**
[MockOnepipeClient.newInvoice](../src/main/java/com/onepipe/integration/MockOnepipeClient.java)
assigns every new invoice a virtual-account expiry one hour after creation,
including installment and unlimited subscription invoices.
[PaymentService.expireOverduePayments](../src/main/java/com/onepipe/service/PaymentService.java)
runs every 60 seconds, loads every `PENDING` payment and, when its stored
`virtualAccountExpiryDate` is before the current time, saves the entire payment
as `CANCELLED`. It does not check payment type, billing frequency, first/next
billing dates or whether any recurring installment is due. Other statuses are
not selected by this job.

Consequently, on a scheduler run after that one-hour expiry, a pending installment
plan or unlimited subscription can be cancelled locally before its first recurring
charge is due. Even the earliest supported recurring interval is one day. An
unlimited plan's null end date therefore does not currently guarantee that its
database status will remain pending until an intentional lifecycle transition.
The job does not call `cancelMandate` or update mock provider state: a mock query
can continue reporting `Pending` while the database says `CANCELLED`.

**High-priority Task 8 requirement:** distinguish the lifetime of an invoice's
virtual account from the lifetime of the installment plan or subscription.
Virtual-account expiry alone must not imply subscription termination, cancellation
of the entire installment plan, payment confirmation or discharge of future
obligations. Define account/invoice expiry separately from recurring-plan
termination and explicitly reconcile database/provider lifecycle state. Single
invoice expiry and authorized explicit cancellation need their own agreed rules.
Do not infer undocumented OnePipe account-renewal or mandate behavior. This
documentation correction leaves the scheduler, payment logic and webhooks
unchanged; it implements neither Task 7 nor Task 8.

Recommended regression tests for the future Task 8 correction:

- Use an injected or otherwise controllable clock. Parameterize `INSTALLMENT`
  and `SUBSCRIPTION` across `DAILY`, `WEEKLY` and `MONTHLY`: advance past account
  expiry while first/next billing is still in the future, run expiry handling,
  and verify the entire plan is not cancelled. Preserve stored amounts, planned
  schedule, counts and confirmed-payment evidence; make no mandate-cancellation
  call merely because the account expired.
- Cover expiry before and after a recurring due date. Account expiry alone must
  neither cancel the entire recurring arrangement nor fabricate a collection or
  successful payment. Due-date reconciliation must follow the separately defined
  Task 8 policy.
- Run expiry/reconciliation repeatedly and verify idempotent handling and
  consistent, explicitly reconciled database/provider state. Cover pending and
  active plans, recorded settlements and already-cancelled plans without reviving
  terminal records or overwriting confirmed accounting evidence.
- Define and test just-before, exactly-at and just-after expiry boundaries, plus
  absent and malformed expiry values. A bad timestamp must not stop valid records
  from being processed. Keep separate tests for the agreed single-payment expiry
  policy and authorized explicit subscription cancellation.
- Add an H2/mock integration case using an actual mock invoice: advance beyond
  its one-hour virtual-account expiry but before the first recurring due date,
  invoke expiry handling deterministically, and verify the plan remains viable
  with the expected mock state and no fabricated payment. No real provider calls
  or hour-long sleeps are needed.

Existing tests assert the mock's one-hour expiry and initial recurring terms,
but no current test exercises `expireOverduePayments()`. The previously passing
400-test Task 6 suite does not establish correct recurring-plan expiry behavior.
These are recommendations only; no regression tests or lifecycle correction are
implemented in this documentation change.

## Validation and frontend compatibility

Jakarta Bean Validation checks DTO fields; the services run financial validation
after database-backed resource authorization. The `FinancialInput` validation
group deliberately delays those checks until ownership/branch checks finish.
Controller `@Valid` also checks the invoice target ID. JSON enum ordinals and
fractional numbers for integer IDs/counts are rejected. Invalid JSON or payment
data returns the existing structured ErrorResponse with HTTP 400; genuine
ongoing-invoice/identity conflicts retain HTTP 409. No submitted secrets or bank
details are echoed in validation messages. Invalid requests make no provider
calls or writes. Task 5 parent-only plan switching and administrative reads/query
permissions remain in force. Subscription frequency is now retained on switching.

Existing response field names are retained. An additive `terms` object on payment
details, parent payments and branch payments includes currency, amountPerCycle,
exact installmentAmounts, frequency, firstBillingDate, endDate, remainingAmount
and balanceBasis. A single payment has no recurring terms; subscription endDate
and finite aggregate balance are null. The registration request accepts legacy
`numberOfPayments` as an alias for `numberOfInstallments`; both mean installments
after the down payment. Prefer the canonical field and do not send both.

Frontend source was reviewed read-only. Follow-up frontend work is required:

- `src/lib/feeConfig.ts` still lists 500 per class, rounds installment previews
  to whole naira and displays no cents. It must use the configured fee/returned
  planned schedule and display two decimals.
- `ParentDashboard.tsx` and `BranchAdminDashboard.tsx` still submit fixed 500 for
  school fees. Omit the optional school-fee amount or submit the correct server
  fee; old conflicting requests now correctly receive 400.
- `RegistrationWizard.tsx` submits the old fee as amount. Its legacy count alias
  is supported, but the amount must match or be omitted. Subscription choices
  must explicitly send frequency.
- Dashboards and `PaymentDetailsModal.tsx` must show unknown/unbounded balances
  as such rather than treating null as zero or falling back to a claimed debt.
  Raw legacy counters must not be presented as verified payment progress.
- Mock account strings are synthetic and never transfer instructions. Existing
  transfer-oriented UI instructions need a separate mock-mode presentation change.

No frontend code, authentication policy, seed data, database profile, provider
cryptography, migrations or deployment settings are changed by Task 6.
The real-only super-admin connection diagnostic retains its route but now
requires `transactionRef` and only queries the adapter. Its old hardcoded
amount/down payment, daily schedule, demo biller and untracked invoice creation
were removed. It remains absent in mock mode.
