# Mock OnePipe demonstration

`onepipe.mode=${ONEPIPE_MODE:mock}` selects the payment adapter. The default is
`mock`; `real` is an explicit opt-in. Values are case-insensitive. Empty values,
surrounding whitespace, unsupported values, or anything other than exactly one
selected provider cause startup to fail with a configuration error.

Mock mode does not resolve or require `ONEPIPE_BASE_URL`, `ONEPIPE_API_KEY`,
`ONEPIPE_CLIENT_SECRET`, or `ONEPIPE_WEBHOOK_URL`. Do not supply historical
internship credentials. `JWT_SECRET` remains independently required: use a locally
generated private Base64 signing key containing at least 32 random bytes.
Seeding remains disabled unless explicitly enabled with the Task 1 settings.
Spring Boot does not automatically load a `.env` file.

## Safe local startup before Task 3

Database/profile separation is still pending. Until then, explicitly override the
existing PostgreSQL dialect when running against the H2 in-memory default:

```sh
# Set JWT_SECRET privately in your shell first; do not paste it into tracked files.
# No OnePipe environment variables are needed.
mvn spring-boot:run -Dspring-boot.run.arguments="--onepipe.mode=mock --spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect --server.address=127.0.0.1"
```

This override is temporary local configuration, not a new database profile.
Do not use an in-memory database as persistent hosted storage.

## Mock behavior

- Merchant creation returns a `MOCK-` biller identifier stable for the same request
  reference. It never registers a real merchant.
- Invoice creation stores one invoice per transaction reference. Repeating the
  reference returns the original invoice information, including its timestamps.
  A repeated request does not create a new invoice or apply changed input terms.
- New invoices are `Pending`. Querying a known reference returns its current mock
  state. Unknown references return `NotFound`, not success.
- Cancellation uses the existing boolean contract. A matching known subscription
  becomes `Cancelled`; repeating that cancellation remains successful. Unknown or
  mismatched records return `false`.
- State lives in thread-safe maps inside this application process. Restarting the
  application loses mock merchant/invoice state. Database persistence does not
  persist this simulated provider state; a query after restart can return
  `NotFound` for an older database record.
- Returned bank/account strings explicitly identify demonstration data and cannot
  be used as real bank instructions. No real bank account is created, no debit
  occurs, no provider messaging is sent, and no payment QR code is generated.
  The existing nullable QR response fields remain available.
- Mock invoices have a fixed one-hour demonstration expiry, formatted as
  `yyyy-MM-dd HH:mm:ss` for compatibility with the existing local expiry job.
- Returned response objects are copies; modifying one cannot change stored state.

The mock provider never returns `Successful` and exposes no settlement simulation
endpoint. Authenticated payment simulation is a later task. The existing public
webhook can still change local database payment state independently of the mock;
its verification and state-handling repairs remain pending. Authorization,
reconciliation, and payment calculations are not repaired by this adapter change.

## Real adapter boundary

Only `real` mode creates `RealOnePipeClient` and the legacy `/api/test` diagnostics.
It requires the environment-backed provider settings in
`application-example.properties`. Merely starting that adapter does not validate
the historical provider contract. Do not exercise real financial operations as
part of this portfolio demonstration.

`PaymentService` still assembles the same invoice terms and maps the same response
fields. It supplies the payment context through a backward-compatible
`OnepipeClient.sendInvoice(request, payment)` overload. Only the real adapter uses
that context for TripleDES bank authorization. The mock ignores it and has no
HTTP client, provider secret, signature, or encryption dependency. The original
one-argument method remains for the real-only diagnostic controller.

The legacy `isDemoMode=true` in `PaymentService` is **not** provider selection.
It still sets an installment down payment of 10,000 kobo and daily recurring
metadata; subscription metadata still uses daily recurrence and a short date
window. These settings can differ from stored plan amounts/frequency. They remain
unchanged for a later payment-business-rule task.

Mock replay consistency is not webhook idempotency. No OnePipe webhook signature
specification is assumed or implemented here.
