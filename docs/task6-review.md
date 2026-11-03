# Task 6 independent review

Branch: `fix/secure-demo-config`. Baseline commit:
`a6ecf5eef5e50807947c3176daca51d3e534e7fe`.
The initial working tree was clean. Task 6 changes remain uncommitted.
No push, merge, deployment, history rewrite or external service change was made.

## Result and financial rules

- Removed PaymentService's hardcoded demo flag, fixed down-payment override,
  forced daily billing, three-day/three-month subscription windows and fallback
  installment count/monthly frequency. The real-only connection diagnostic now
  performs a read-only reference query rather than building another fixed-price
  sandbox invoice with a demo biller and daily schedule.
- NGN amounts use BigDecimal with two decimal places. Invalid submitted precision,
  nonpositive values and values outside the existing numeric(38,2) mapping are
  rejected. Provider construction converts once to integer kobo; adapters do not
  convert again.
- The server's configurable synthetic class fee schedule controls initial and
  subsequent tuition invoices. Matching submitted amounts are optional; conflicting
  amounts are rejected. Existing invoice snapshots are preserved. Ad-hoc invoices
  retain legitimate caller-supplied amounts.
- Single payment: full finite invoice, no down payment or recurrence, initially
  pending and fully outstanding.
- Installment: planned 20% down payment rounded HALF_UP; divide the remainder in
  integer kobo and allocate leftover kobo to the earliest installments. Count means
  installments after the down payment. No collection is presumed received.
- Example: 100000.00 total = 20000.00 down + 26666.67 + 26666.67 + 26666.66.
  The provider request uses 10000000 kobo total and 2000000 kobo down.
- Subscription: the user-approved unlimited per-cycle model. Selected frequency,
  first billing one interval after invoice creation, no down payment, finite count
  or automatic end date in the planned terms. The unchanged expiry job can still
  cancel a pending recurring plan after its mock account expires; the high-priority
  Task 8 defect is documented below. A school-fee subscription charges the
  configured fee per cycle; it is not a finite tuition repayment schedule.
- New finite unpaid invoices retain their full balance. Recorded finite settlement
  reports 0.00 with REPORTED_SETTLED. Other non-pending states or nonzero legacy
  counters cannot establish partial collections and report an unknown balance.
  Unlimited subscriptions have an unbounded aggregate balance. Null is not zero.

Detailed policies, request semantics, sample fee settings, rounding, provider
limitations and frontend implications are in [payments.md](payments.md).

## Complete changed-file inventory

Paths are project-relative. N = new; M = modified. This includes this review report.

| File | Change and reason |
| --- | --- |
| `README.md` | M: replace outdated financial-calculation status and document read-only diagnostic. |
| `docs/authorization.md` | M: retain policy, explain additive terms/count alias and label the historical Task 5 test result. |
| `docs/mock-onepipe.md` | M: remove obsolete sandbox claims; document captured monetary terms, units and real-provider limits. |
| `docs/payments.md` | N: financial rules, fee configuration, API implications and deferred event-accounting design. |
| `docs/task6-review.md` | N: review summary, complete file inventory and verification results. |
| `src/main/java/com/onepipe/config/PaymentJsonConfiguration.java` | N: reject numeric enum ordinals and fractional integer inputs. |
| `src/main/java/com/onepipe/config/SchoolFeeSchedule.java` | N: load, validate and optionally override one server fee source. |
| `src/main/java/com/onepipe/controllers/PaymentController.java` | M: validate target ID, pass complete request to the authorized service, retain routes. |
| `src/main/java/com/onepipe/controllers/StudentController.java` | M: add @Valid at the request boundary; financial group runs after identity authorization. |
| `src/main/java/com/onepipe/controllers/TestController.java` | M: replace sandbox invoice generation with a reference query; retain super-admin/real-mode restrictions. |
| `src/main/java/com/onepipe/data/enums/ClassGrade.java` | M: remove historical all-500 fees from the class identifiers. |
| `src/main/java/com/onepipe/dtos/request/PaymentRequest.java` | M: validate fields, money and count; support explicit recurring ad-hoc terms. |
| `src/main/java/com/onepipe/dtos/request/RegisterStudentRequest.java` | M: validate registration/payment fields, accept optional matching amount and legacy count alias. |
| `src/main/java/com/onepipe/dtos/request/SwitchPlanRequest.java` | M: validate required type, count and banking field lengths. |
| `src/main/java/com/onepipe/dtos/response/BranchPaymentDto.java` | M: add planned terms while retaining existing fields. |
| `src/main/java/com/onepipe/dtos/response/ParentPaymentDto.java` | M: add planned terms while retaining existing fields. |
| `src/main/java/com/onepipe/dtos/response/PaymentTerms.java` | N: explicit currency, cycle amount, exact schedule, dates and balance basis. |
| `src/main/java/com/onepipe/dtos/response/RegisterStudentResponse.java` | M: add planned terms to existing payment details. |
| `src/main/java/com/onepipe/exceptions/GlobalExceptionHandler.java` | M: structured safe 400 responses for validation, malformed JSON and parameters. |
| `src/main/java/com/onepipe/exceptions/customExceptions/InvalidPaymentException.java` | N: controlled financial-validation failures without rejected sensitive values. |
| `src/main/java/com/onepipe/integration/MockOnepipeClient.java` | M: immutable financial snapshots, replay/query consistency, no payment execution. |
| `src/main/java/com/onepipe/integration/dto/OnePipeResponse.java` | M: optional local mock diagnostic fields for request-unit financial snapshots. |
| `src/main/java/com/onepipe/service/PaymentCalculator.java` | N: independently testable exact NGN/kobo calculations, schedules and first dates. |
| `src/main/java/com/onepipe/service/PaymentService.java` | M: authoritative invoice terms, sandbox removal, cautious balances, validation after authorization and retained subscription frequency. |
| `src/main/java/com/onepipe/service/StudentService.java` | M: validate before any registration writes, use the same fee rules and return planned terms. |
| `src/main/java/com/onepipe/validation/FinancialInput.java` | N: validation group that preserves authorization ordering. |
| `src/main/java/com/onepipe/validation/PaymentInputValidation.java` | N: invoke Jakarta validation in authorized service paths. |
| `src/main/resources/school-fees.properties` | N: explicitly synthetic, configurable class tuition samples. |
| `src/test/java/com/onepipe/config/SchoolFeeScheduleTest.java` | N: all classes, overrides, environment-variable spelling and safe invalid-configuration failure. |
| `src/test/java/com/onepipe/controllers/TestControllerTest.java` | N: query-only diagnostic, blank-reference rejection and no invoice/provider side effects. |
| `src/test/java/com/onepipe/integration/MockOnepipeClientTest.java` | M: immutable financial replay snapshots and additive response fields. |
| `src/test/java/com/onepipe/integration/RealOnePipeClientAuthTest.java` | M: captured transport proves kobo is not multiplied again; no real calls. |
| `src/test/java/com/onepipe/security/AuthorizationIntegrationTest.java` | M: valid registration fixtures and correct retained subscription frequency; all authorization assertions preserved. |
| `src/test/java/com/onepipe/service/PaymentBalanceTest.java` | N: unpaid, recorded settlement, unknown partial state and unbounded subscription balances. |
| `src/test/java/com/onepipe/service/PaymentCalculatorTest.java` | N: exact totals, rounding, counts, precision, units and all frequency intervals. |
| `src/test/java/com/onepipe/service/PaymentFinancialIntegrationTest.java` | N: actual JWT/security filters, H2, mock provider, HTTP validation and complete no-side-effect snapshots. |
| `src/test/java/com/onepipe/service/PaymentServiceMockIntegrationTest.java` | M: wire new calculation/validation components without provider credentials. |
| `src/test/java/com/onepipe/service/PaymentServiceTransactionReferenceTest.java` | M: retain UUID/reference checks using the new server fee source and components. |

## Compatibility and remaining work

Existing payment response field names remain, with additive `terms`. The new
invoice response also populates its existing onePipePaymentId field consistently.
Unknown or unlimited balances are intentionally nullable. The registration count
alias is supported. Required recurring configuration, positive amounts, valid
precision and matching submitted school fees are now enforced.

Frontend files were only read. Its fixed fee submissions now receive 400 until
omitted or corrected. Whole-naira previews/displays, null-as-zero balances,
subscription selection and raw-counter progress displays need a separate frontend
change; exact local terms are available from the backend. The historical real
diagnostic requires transactionRef and no longer creates invoices. No frontend
route/response fields were removed.

No authoritative OnePipe variable-final-installment capability is available.
The local schedule is exact, but the existing provider request only carries its
existing total/down/count/frequency/date fields. No undocumented fields or
provider signing changes were introduced. No real provider operation was run.

Task 7 must introduce verified, durable, deduplicated collection events and
subscription cycle obligations before accurate partial balances can be derived.
Existing raw counters are not payment evidence. Webhook signatures and
deduplication remain pending. **High-priority Task 8: Payment Lifecycle and
Reconciliation** must separate virtual-account expiry from recurring-plan
termination. The current job can cancel any expired pending database payment,
including an installment plan or unlimited subscription before a future charge
is due, while the mock still reports `Pending`. See [the verified behavior and
recommended regression tests](payments.md#task-8-payment-lifecycle-and-reconciliation).
Neither Task 7 nor Task 8 is implemented by this documentation correction.
WebhookController, WebhookService and the scheduler were not modified. Database
profiles, seeding, migrations, security policy and Render settings were not
changed. This does not establish production payment integrity.

## Verification

Final offline Maven `test` run: BUILD SUCCESS. Maven compiled all 77 application
sources and 17 test sources. **400 tests passed: zero failures, errors or skipped
tests**, including the 89 authorization integration cases and six authorization
unit cases. This is 146 additional cases over the Task 5 baseline of 254.

The final run used JUnit 5, Mockito, actual Spring Security filters and signed test
JWTs, isolated H2 databases and the offline mock/captured transport. No PostgreSQL
or real OnePipe connection was used. The existing unchecked-operation compiler
warning in unchanged RealOnePipeClient remains; there were no compilation errors.

The original focused run passed 223 tests. Two full runs during review passed
390 and 395 tests; the final complete run includes all subsequent validation
and diagnostic corrections and passes 400. `git diff --check` passed. A programmatic
comparison confirms that this 38-file inventory exactly matches the working-tree
change set. Protected-scope checks confirm no webhook, security-policy, seeder,
database-profile or real-provider implementation changes.

Final suite breakdown:

| Test class | Passing cases |
| --- | ---: |
| DataSeederIntegrationTest | 22 |
| DataSeederTest | 29 |
| EnvironmentConfigurationTest | 69 |
| SchoolFeeScheduleTest | 13 |
| TestControllerTest | 5 |
| DevApplicationStartupTest | 1 |
| MockApplicationStartupTest | 1 |
| MockOnepipeClientTest | 16 |
| OnepipeProviderSelectionTest | 13 |
| RealOnePipeClientAuthTest | 6 |
| AuthorizationIntegrationTest | 89 |
| ResourceAuthorizationTest | 6 |
| PaymentBalanceTest | 19 |
| PaymentCalculatorTest | 34 |
| PaymentFinancialIntegrationTest | 71 |
| PaymentServiceMockIntegrationTest | 3 |
| PaymentServiceTransactionReferenceTest | 3 |
| **Total** | **400** |

The final review correction changed documentation only. The 400-test result above
belongs to the preceding Task 6 implementation run; Maven was not rerun for this
documentation correction. Existing tests do not exercise the expiry scheduler,
so that result does not verify correct recurring-plan expiry. Future Task 8
regression tests are recommended in payments.md and have not been added here.

No further implementation is authorized here. Await independent review before
committing Task 6 or beginning payment-event work.
