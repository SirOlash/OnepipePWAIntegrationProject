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
