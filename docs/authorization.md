# Authorization and tenant policy

This Task 5 policy was recorded before implementation and verified by the tests
described below. Greenfield remains a prototype.

The authenticated identity comes from Spring Security after JWT verification.
The database User role and User -> Parent or User -> Branch relationship determine
access. Request emails, IDs, role fields and frontend state never grant access.
Missing or ambiguous account associations fail closed.

| Operation | SUPER_ADMIN | BRANCH_ADMIN | PARENT | Anonymous |
|:----------|:------------|:-------------|:-------|:----------|
| Branch discovery | Full administrative details | Full details for own branch; public fields for others | Public fields | Public fields |
| Create branch; platform statistics; historical diagnostics | Allowed | Denied | Denied | Authentication required |
| Branch student/payment lists | Any branch | Own branch only | Denied | Authentication required |
| Students selected by parent email | Any parent | Only that parent's students within own branch | Own authenticated parent only | Authentication required |
| Student payments; new invoice; payment query/cancel | Any consistent record | Own branch only | Own children/payments only | Authentication required |
| Change student payment plan | Denied | Denied | Own children only | Authentication required |
| Supply replacement parent banking details | Denied | Denied | Own parent only | Authentication required |
| Register using an existing parent | Denied | Denied | Own authenticated parent only | Identity conflict; sign in first |
| Register a genuinely new parent/student | Allowed | Own branch only | Cannot create another parent identity | Allowed |
| Simulate a collection (explicit dev/mock opt-in only) | Allowed | Denied | Denied | Authentication required |

Public branch fields are `id`, `businessName`, `branchCode` and `address`.
The existing branch-response shape is retained; biller and administrator/contact
fields are null outside the authorized administrative view. Public registration
needs branch selection, not administrator identity or provider identifiers.

Student access follows persisted parent and branch relationships. Payment access
also checks that its stored student, parent and branch agree; inconsistent records
are rejected. Parent-email lookups by branch administrators are restricted in the
database query, so children at other branches never appear. A parent cannot use
a branch-wide listing even if one child attends that branch.

All HTTP-facing payment operations authorize before provider calls, writes,
banking changes or reconciliation. Internal initial-invoice creation is restricted
to the registration service's validated flow; it is not an unauthenticated general
payment entry point. Real webhook submissions now fail closed with 503 without
processing financial data. The dev-only simulation surface requires SUPER_ADMIN
at HTTP, method and database-role checks, plus an explicit dev/mock environment
guard. Scheduled expiry remains separate lifecycle work for Task 8.

Payment-plan changes are exclusively for the authenticated parent who owns the
student. Branch and super administrators receive 403 for every plan-change
request, including their own branch's students and requests that supply no banking
details. This restriction covers payment type, installment frequency and recurring
configuration; administrative read and payment-query permissions stay unchanged.

Existing-parent registration requires the authenticated PARENT's own email and
phone identity. It reuses that Parent entity without replacing its profile,
password or bank details. Conflicting banking input is rejected. Administrative
roles cannot attach children to an existing parent without that parent's verified
identity. New registration rejects any existing email or phone collision instead
of reusing a User, changing a role, or linking an unrelated Parent.

HTTP errors use the existing structured `ErrorResponse` fields: `code`, `message`,
`status`, `timestamp`, `path`. Missing, expired or invalid authentication returns
401; insufficient access returns 403; missing resources return 404. A branch
administrator's parent-email lookup without an in-branch child returns 404 to
conceal whether that parent exists elsewhere. Genuine identity/state conflicts
return 409. Authorization errors do not reveal account or payment details.

## Frontend compatibility

The local frontend's Axios interceptor already sends the stored bearer JWT. Its
parent-email, branch-ID, student-ID and OnePipe-payment-ID request forms remain
supported, subject to server-side checks. Existing successful response fields
are retained; Task 6 adds planned `terms` and corrects financial calculations
as described in [payment rules](payments.md). Cancel/query path IDs still mean
the provider payment identifier, not the numeric database row ID.

Existing parents must sign in before registering another child. The wizard should
explain the generic 409 identity conflict and offer sign-in, prefill the signed-in
parent identity, and avoid proposing a password or bank replacement in that flow.
Changing banking details belongs to the authenticated owner's plan-change flow.
Administrative plan changes receive 403 even for an otherwise accessible student;
admin interfaces should not offer plan-change controls. Frontend source is
reviewed read-only; these UI adjustments are not part
of this backend change.

The registration branch selector uses the public fields above. Administrative
branch views must tolerate null private fields for branches outside their scope.
The current frontend also has a hardcoded fallback branch and sends
`numberOfPayments`; Task 6 accepts that registration field as an alias for
`numberOfInstallments` (recurring installments after the down payment).
The hardcoded branch fallback still needs a separate frontend change.

## Boundaries

Public new-parent signup does not verify ownership of a previously unregistered
email or phone. No incomplete OTP scheme is added. Rate limiting, contact
verification, token revocation, restrictive CORS and real webhook verification
remain separate work. Task 7 provides durable idempotency and collection-derived
states for simulated confirmations, while Task 8 lifecycle reconciliation remains
pending.
The public webhook cannot change payment state: Task 7 rejects it with 503 until
authoritative verification is available. The collection ledger confirms only
simulated events through the guarded dev mechanism; this policy does not
establish production payment integrity.
No production readiness, hosted deployment or PostgreSQL verification is claimed.

## Verification

At Task 5 review, Maven compilation and the complete 254-test suite passed, with no failures,
errors or skipped tests. The new coverage consists of 89 MockMvc/service
integration cases and six focused authorization unit tests. Integration tests
use actual Spring Security filters, signed JWTs, H2 and the real offline mock
adapter, with two parents, three students, two branches, two branch admins and a
super admin. One parent has children at both branches to exercise both ownership
and branch-scoped email searches.

Tests verify legitimate access, cross-tenant denials in both directions, direct
service enforcement, missing associations, inconsistent payment relationships,
invalid/expired tokens, ignored role claims, registration takeover prevention,
banking ownership, public branch redaction and HTTP error statuses. Rejected
operations compare complete database snapshots and assert no mock-provider calls;
cross-tenant cancellation attempts also leave the target mock invoice pending.
The previous configuration, provider, reference-generation and seeding tests
remain passing. No PostgreSQL or real OnePipe connection is used.

The final parent-only plan policy is tested across all three payment types.
Parents can change their own child's plan and cannot change another parent's
child's plan. Branch and super admins receive 403 with or without banking fields,
including through direct service calls. Rejected administrative plan changes
preserve complete database and mock-response snapshots and make no provider
calls. Administrative read and payment-query regression tests remain passing.
