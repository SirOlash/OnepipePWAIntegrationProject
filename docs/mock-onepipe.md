# Mock OnePipe demonstration

`onepipe.mode=${ONEPIPE_MODE:mock}` selects the payment adapter. The default is
`mock`; `real` is an explicit opt-in permitted only under the `dev` profile.
The `test` and `hosted` profiles require mock mode. Values are case-insensitive. Empty values,
surrounding whitespace, unsupported values, or anything other than exactly one
selected provider cause startup to fail with a configuration error.

Mock mode does not resolve or require `ONEPIPE_BASE_URL`, `ONEPIPE_API_KEY`,
`ONEPIPE_CLIENT_SECRET`, or `ONEPIPE_WEBHOOK_URL`. Do not supply historical
internship credentials. `JWT_SECRET` remains independently required: use a locally
generated private Base64 signing key containing at least 32 random bytes.
Seeding remains disabled unless explicitly enabled with the Task 1 settings.
Spring Boot does not automatically load a `.env` file.

## Local startup

Select exactly one profile explicitly: `dev`, `test` or `hosted`. Missing,
unsupported or mixed profiles fail startup; there is no implicit H2 fallback for
hosted use. After [generating a private JWT key](../README.md#2-set-environment-variables),
run the local H2 demo with:

```sh
# Set JWT_SECRET privately in your shell first; do not paste it into tracked files.
# No OnePipe environment variables are needed.
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

The `dev` profile selects H2, creates a temporary schema and binds HTTP to
`127.0.0.1`. No manual dialect override is needed. The `test` profile uses isolated
H2 databases and disallows demo seeding. The `hosted` profile requires supplied
PostgreSQL settings and an existing compatible schema. It cannot initialize an
empty database until later migrations are implemented. See
[configuration details](configuration.md) for IDE setup, hosted prerequisites and
the test command. No hosted deployment or database connection has been verified.

## Mock behavior

- Merchant creation returns a `MOCK-` biller identifier stable for the same request
  reference. It never registers a real merchant.
- Invoice creation stores one invoice per transaction reference. Repeating the
  reference returns the original invoice information, including its timestamps.
  A repeated request does not create a new invoice or apply changed input terms.
- New invoices are `Pending`. Querying a known reference returns its current mock
  state. Unknown references return `NotFound`, not success.
- The adapter's standalone boolean cancellation contract still modifies its
  process-local metadata, but application cancellation no longer calls it.
  The locked database lifecycle is authoritative; an authorized cancellation
  preserves the ledger and succeeds idempotently without a pre-commit map write.
- State lives in thread-safe maps inside this application process. Restarting the
  application loses mock merchant/invoice state. Database persistence does not
  persist this simulated provider state; a query after restart can return
  `NotFound` for an older database record.
- Returned bank/account strings explicitly identify demonstration data and cannot
  be used as real bank instructions. No real bank account is created, no debit
  occurs, no provider messaging is sent, and no payment QR code is generated.
  The existing nullable QR response fields remain available.
- Mock invoices have a fixed one-hour demonstration expiry, formatted as
  `yyyy-MM-dd HH:mm:ss` in Africa/Lagos using the injected Clock. The expiry job
  records a separate marker; it never cancels an installment or subscription.
  A single-payment window closes at now >= expiry and unknown metadata fails
  closed for new FULL confirmations. Original event replays remain valid.
  See [lifecycle rules](payment-lifecycle.md).
- Returned response objects are copies; modifying one cannot change stored state.

The mock invoice adapter never returns `Successful`; it remains an in-memory
invoice/mandate simulator. Task 7 adds a separate, explicitly enabled dev-only
super-admin [collection simulation endpoint and persistent ledger](payment-events.md).
It confirms simulated collections without calling the adapter or modifying its
maps. Ledger state and balances are authoritative for these simulated collections;
mock invoice queries can still say `Pending` after a simulated ledger settlement.
The public webhook now returns 503 without parsing/logging a payload or mutating
financial state. Real verification and trusted bank reconciliation remain unavailable.
Financial calculations are described in [payment rules](payments.md).

## Real adapter boundary

Only explicit `real` mode under `dev` creates `RealOnePipeClient` and the legacy `/api/test` diagnostics.
The diagnostics require `SUPER_ADMIN`; mock mode exposes neither diagnostic route.
HTTP-facing student/payment operations also enforce the
[parent ownership and branch authorization policy](authorization.md) before
provider calls or writes. Public new-parent registration is the documented
exception for creating its own initial invoice; existing-parent registration
requires authenticated ownership.
It requires the environment-backed provider settings in
`application-example.properties`. Merely starting that adapter does not validate
the historical provider contract. Do not exercise real financial operations as
part of this portfolio demonstration.

`PaymentService` assembles validated invoice terms using the existing request
fields. It supplies the payment context through a backward-compatible
`OnepipeClient.sendInvoice(request, payment)` overload. Only the real adapter uses
that context for TripleDES bank authorization. The mock ignores it and has no
HTTP client, provider secret, signature, or encryption dependency. The original
one-argument invoice method remains on the adapter interface. The real-only
connection diagnostic queries a supplied `transactionRef` and no longer builds
fixed-amount sandbox invoices.

The historical sandbox amount and recurrence overrides have been removed.
`PaymentService` converts the actual invoice and planned down payment from NGN
to integer kobo exactly once. Both adapters receive those same provider units;
neither adapter converts again. The mock snapshots amount, down payment, count,
frequency and dates and returns copies on replay/query. The optional
`amountInKobo`, `downPaymentInKobo` and recurrence fields in `OnePipeResponse`
are local mock diagnostics, not claims about undocumented OnePipe response fields.
The API's existing payment amounts and new planned `terms` remain in NGN.

The request retains only the existing amount/down-payment/count/frequency/date
fields. No per-cycle amount list or final-installment field has been invented
for OnePipe. The exact variable installment schedule is calculated locally and
returned in API `terms`; historical real-provider rounding and recurring-field
semantics cannot be verified against the discontinued internship environment.
The mock adapter records planned terms; guarded simulation records collections
only in the local ledger and never executes a bank charge. The subscription
request has no automatic end date. Account expiry is separate from its
billing and termination. New simulated cycles must be due and sequential,
anchored to the persisted original billing date; expiry never cancels the plan.
Explicit cancellation changes only the locked database lifecycle. An adapter
Pending response afterward is a reported diagnostic disagreement, not financial
authority. Lost metadata after restart is reported UNAVAILABLE; queries never
recreate invoices, account details or collection records.

Mock replay consistency is not webhook idempotency. No OnePipe webhook signature
specification is assumed or implemented here.
