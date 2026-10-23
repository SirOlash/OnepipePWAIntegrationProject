# Payment calculations and validation

Greenfield remains an internship prototype. These rules describe planned
obligations in offline mock mode, not verified money movement. Invoice creation
leaves status `PENDING` and `completedPayments=0`. Task 7 adds an explicitly
guarded dev-only simulation endpoint and persistent collection ledger, described
in [payment events](payment-events.md). Real webhooks fail closed; no signature
scheme or real-money processing is claimed.

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
the invoice does not settle it. Only persisted confirmed collection evidence
settles a ledger-managed invoice and produces a zero balance. Historical
`SUCCESSFUL` without trustworthy evidence now remains `UNKNOWN`, not zero.

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

First recurring billing is one selected interval after the persisted original
billing anchor, using the injected payment clock and Africa/Lagos zone. Each
ordinal is calculated from that same anchor: January 31 -> February 28/29 ->
March 31. Due installment slots can be late or out of order, but future slots
cannot be newly confirmed. Down payment remains a separate immediately eligible
slot. See [billing and expiry policies](payment-lifecycle.md).

The existing OnePipe request can represent total/down/count/frequency/start date,
but has no documented field here for a variable final installment. No new
provider fields are invented. Exact local scheduling is tested; real-provider
rounding or variable collection support remains unverified. The mock adapter
sends no provider requests or automatic charges. Guarded simulation records only
local synthetic collections.

## Unlimited subscriptions

The approved model treats the invoice amount as the amount **per billing cycle**.
For a school-fee subscription this means the configured class fee on every cycle,
not repayment of one finite tuition bill. ADHOC subscriptions use their specified
cycle amount. Choose this plan deliberately; a finite obligation uses installments.

Frequency is required. First billing is one selected interval after the original
persisted anchor. The plan has no down payment, automatic end date or finite
installment count. New cycle confirmations must be due and sequential; late
cycles remain eligible. Account expiry does not terminate the subscription.
Authorized explicit cancellation ends its local lifecycle while preserving all
confirmed cycles. No automatic debit occurs.

Unlimited subscriptions have no aggregate finite `remainingAmount`; it returns
null with `terms.balanceBasis=UNBOUNDED_SUBSCRIPTION`. Confirmed cycle amounts
are persisted individually in the collection ledger. No separate future-cycle
obligation table is created. The existing `totalAmount` and API `amount` fields
denote the per-cycle charge for this plan, not lifetime debt.


## Confirmed balances and accounting

New invoices have `accountingVersion=1`; their finite balance is invoice total
minus the sum of their persisted confirmed collections. Merely planning a down
payment never reduces it. Fully confirmed finite invoices report 0.00; overpayment
is rejected rather than silently capped. `terms.confirmedAmount` is additive and
reports the ledger sum. Ledger-backed progress uses `CONFIRMED_COLLECTIONS`, fresh
unpaid invoices use `UNPAID`, and unlimited subscriptions retain a null finite
balance with `UNBOUNDED_SUBSCRIPTION`.

Historical rows have no accounting marker and remain `UNKNOWN`, including legacy
`SUCCESSFUL` flags. They cannot be confirmed through the new processor without a
future evidence-based migration/reconciliation policy. No events are backfilled
from counters. Raw historical counters remain compatibility data, not payment
evidence. New counters are derived from the ledger; installment counters exclude
the down payment. Marker-bearing but inconsistent empty-ledger states also remain
unknown and cannot be adopted silently. Null balances/confirmed amounts are not zero.

The [payment-event design](payment-events.md) describes atomic confirmation,
identity uniqueness and explicit transitions. Real webhook submissions now fail
closed with 503. Provider queries remain authorized but read-only, even when a
provider claims success/failure. Task 8 separates expiry, scheduling and
cancellation from financial confirmation; see [payment lifecycle](payment-lifecycle.md).

## Payment lifecycle and reconciliation

Task 8 replaces the previous recurring-plan expiry defect with separate account
expiry markers and a single-payment window policy. Expiry never changes financial
status, counters or collections. Recurring schedules survive the mock account's
one-hour lifetime. Single-payment windows close at the expiry boundary; missing
or malformed single-payment expiry fails closed for new confirmations.

Cancellation, expiry and confirmation serialize on the same payment row.
Cancellation uses the committed database lifecycle without changing adapter maps,
so a database rollback cannot leave an externally mutated mock mandate. Original
collection replays are checked before new-event due/window/state rules.

Read-only reconciliation returns local ledger balances separately from
process-local provider availability and disagreement. It never creates a
collection or changes lifecycle state. Historical rows remain unknown; a missing
billing anchor is not automatically reconstructed. See the complete
[lifecycle, schedule, concurrency and schema policy](payment-lifecycle.md).

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
and balanceBasis, plus additive confirmedAmount. A single payment has no recurring terms; subscription endDate
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
