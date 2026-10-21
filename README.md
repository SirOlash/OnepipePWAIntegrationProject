# Greenfield School Payments Demo

![Java](https://img.shields.io/badge/Java-17-orange?style=flat-square&logo=java)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.1.6-brightgreen?style=flat-square&logo=springboot)
![Docker](https://img.shields.io/badge/Docker-Containerized-blue?style=flat-square&logo=docker)

A Java 17 / Spring Boot school-payments prototype originally built for a OnePipe
internship demonstration. It is being improved as a reproducible portfolio demo.
The historical OnePipe credentials and API environment are unavailable, and the
original hosted PostgreSQL database has expired.

Startup requires exactly one explicit profile: `dev`, `test` or `hosted`.
The default payment adapter is an offline mock. Its account identifiers are
synthetic: it creates no bank accounts, sends no transfers or debits, and does not
automatically confirm payment. The historical real adapter remains an explicit
opt-in under `dev`; `test` and `hosted` require mock processing. The historical
adapter does not establish a currently working provider integration.

## Overview

The prototype models branches, parent/student registration, invoices, payment
plans and dashboards. It retains `SINGLE_PAYMENT`, `INSTALLMENT` and `SUBSCRIPTION`
plan types. Legacy amount and recurrence calculations still need review; these
labels do not imply working automatic collection.

Mock merchant/invoice requests return clearly marked demonstration identifiers.
Repeated references reuse process-local state, new invoices start pending, and
unknown queries return `NotFound`. Restarting clears mock adapter state even if
application records persist in PostgreSQL. No payment QR code is generated;
nullable QR fields remain in the response contract.

A scheduled job attempts to expire pending invoices using stored expiry dates.
Query and webhook handlers exist, but payment-state correctness, reconciliation
and webhook security remain unfinished. See
[mock provider behavior and limitations](docs/mock-onepipe.md) for details.
Use synthetic records for this demo.

## Architecture

```text
React frontend
      |
Spring Boot REST API
      |-- Branch / Student / Payment services
      |       `-- OnepipeClient
      |             |-- MockOnepipeClient (default, offline, process-local state)
      |             `-- RealOnePipeClient (explicit opt-in, historical API adapter)
      `-- JPA repositories
            |-- H2 (explicit dev / test profiles; in memory)
            `-- PostgreSQL (explicit hosted profile; existing schema required)
```

Mock mode makes no OnePipe calls. Only explicit real mode under `dev` creates the real
adapter and legacy diagnostic controller. JWT authentication and some role
checks exist; full authorization and tenant isolation are not established.

## Tech stack

| Layer | Technology |
|:------|:-----------|
| Language | Java 17 |
| Framework | Spring Boot 3.1.6 |
| Authentication | Spring Security, JWT and BCrypt |
| Database | Local H2; optional PostgreSQL |
| ORM | Hibernate / Spring Data JPA |
| Payment adapters | Default offline mock; historical OnePipe PWA adapter |
| Historical provider authorization | TripleDES code in the real adapter |
| Build / packaging | Maven and Docker |

## Security and limitations

- JWT signing requires a privately supplied key; no shared signing key is bundled.
- Demo seeding is disabled by default and restricted to `dev`. Explicit opt-in
  requires private credentials. Initialization is transactional, preserves
  existing passwords and roles, and rejects conflicting identities or ownership.
  Hosted startup never runs the seeder, even if its opt-in flag is enabled.
- Branch creation and super-admin statistics enforce `SUPER_ADMIN` method checks.
  Other student/payment routes generally require authentication but do not
  consistently bind requested branch, parent, student or payment identifiers to
  the authenticated user. Role enforcement and ownership checks remain incomplete.
- The webhook route is public. Signature verification is pending an authoritative
  provider contract; no OnePipe webhook signature specification is assumed here.
- Durable webhook idempotency and safe payment-state transitions remain pending.
  Mock reference reuse is process-local replay consistency, not webhook protection.
- Mock mode has no authenticated successful-payment simulation endpoint and does
  not guarantee end-to-end reconciliation.

This internship prototype is not a production payment system. These features do
not establish secure tenant isolation or complete payment validation.

## API endpoints

Access below describes the current code, not the intended role model.
`Authentication required` does not imply an ownership or tenant check.

### Authentication and branches

| Method | Endpoint | Current access | Description |
|:-------|:---------|:---------------|:------------|
| `POST` | `/api/auth/login` | Public | Obtain a JWT for an existing account |
| `POST` | `/api/branches` | Authenticated `SUPER_ADMIN` | Create a branch through the selected adapter |
| `GET` | `/api/branches` | Public | List branches |

### Students

| Method | Endpoint | Current access | Description |
|:-------|:---------|:---------------|:------------|
| `POST` | `/api/students/register` | Public | Register a student and initiate an invoice |
| `GET` | `/api/students?branchId={id}` | Authentication required | List students for the supplied branch |
| `GET` | `/api/students?parentEmail={email}` | Authentication required | List students for the supplied parent |

### Payments

| Method | Endpoint | Current access | Description |
|:-------|:---------|:---------------|:------------|
| `POST` | `/api/payments/new` | Authentication required | Initiate an ad-hoc invoice |
| `GET` | `/api/payments?branchId={id}` | Authentication required | List payments for the supplied branch |
| `GET` | `/api/payments?studentId={id}` | Authentication required | List payments for the supplied student |
| `POST` | `/api/payments/{id}/cancel` | Authentication required | Request subscription cancellation |
| `POST` | `/api/payments/{id}/query` | Authentication required | Query the selected adapter and run legacy status reconciliation |
| `PUT` | `/api/payments/students/{studentId}/switch-plan` | Authentication required | Update the supplied student payment plan |

For cancellation and query, `{id}` is the adapter payment identifier stored as
`onePipePaymentId`, not the numeric database row ID. Student/payment routes still
have the ownership gaps described above.

### Webhooks, statistics and diagnostics

| Method | Endpoint | Current access | Description |
|:-------|:---------|:---------------|:------------|
| `POST` | `/api/webhooks/onepipe` | Public; signature verification pending | Legacy payment notification handler |
| `GET` | `/api/super-admin/stats` | Authenticated `SUPER_ADMIN` | Platform statistics |
| `GET` | `/api/test/encrypt` | Authentication required; real mode only | Legacy encryption diagnostic |
| `GET` | `/api/test/onepipe-connection` | Authentication required; real mode only | Legacy diagnostic that calls the real adapter |

## Getting started

### Prerequisites

- Java 17+
- Maven 3.9+
- A private, locally generated JWT signing key (Base64, at least 32 random bytes)
- H2 is bundled for `dev` and `test`; `hosted` requires PostgreSQL

No OnePipe credentials are needed for the local mock. There is no active profile
default: missing, unsupported or mixed profiles fail startup. See
[configuration and profile details](docs/configuration.md).

### 1. Clone the repository

```bash
git clone https://github.com/SirOlash/OnepipePWAIntegrationProject.git
cd OnepipePWAIntegrationProject
```

### 2. Set environment variables

Generate a private Base64 signing key from 32 random bytes. These commands assign
it to the current shell without printing it. Keep the same private key between
runs if existing JWTs should remain valid.

PowerShell:

```powershell
$jwtBytes = New-Object byte[] 32
$jwtGenerator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $jwtGenerator.GetBytes($jwtBytes)
    $env:JWT_SECRET = [Convert]::ToBase64String($jwtBytes)
} finally {
    $jwtGenerator.Dispose()
}
```

Bash with OpenSSL:

```bash
export JWT_SECRET="$(openssl rand -base64 32)"
```

Spring Boot does not automatically load `.env` or
[application-example.properties](application-example.properties). The example is
a reference; supply settings privately through the shell or IDE. Demo account
seeding is disabled by default.

### 3. Run locally with H2

```bash
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

The `dev` profile uses H2 in memory, creates its schema at startup, binds to
`127.0.0.1` and defaults to mock payments. Data is lost when the application stops.
No manual driver or dialect override is needed.

In IntelliJ, set the Spring Boot run configuration's **Active profiles** to `dev`
and add `JWT_SECRET` privately under **Environment variables**. Do not share a run
configuration containing secrets. See [IDE setup](docs/configuration.md#intellij).

To create local demonstration accounts, explicitly set `DEMO_SEED_ENABLED=true`
under `dev` and privately supply `SUPER_ADMIN_EMAIL`, `SUPER_ADMIN_PASSWORD` and
`DEMO_BRANCH_ADMIN_PASSWORD`, alongside `JWT_SECRET`. No passwords are published
because shared privileged credentials would make a public deployment unsafe.
The synthetic branch-admin identity is `branch-admin@example.invalid`; the
super-admin email must differ. In IntelliJ, set these values privately in the run
configuration's environment; in Maven, inherit them from the current shell.

Repeat runs reuse the same accounts and reserved branch identifiers without
resetting passwords or roles. An orphaned branch-admin account can be repaired
only when its existing password proves ownership and it owns no other branch.
Conflicts fail without overwriting existing data; a failure rolls back the whole
initialization attempt. See [demo seeding](docs/configuration.md#demo-seeding)
for the identifiers, other partial-state rules and concurrency limitations.
H2 data still disappears when the dev application stops. Hosted recruiter
account provisioning remains unimplemented.

### 4. Run with Docker

```bash
docker build -t greenfield-backend .
docker run --rm -p 127.0.0.1:8080:8080 \
  -e JWT_SECRET \
  -e SPRING_PROFILES_ACTIVE=dev \
  greenfield-backend \
  --server.address=0.0.0.0
```

This Bash example passes the private key from the current shell. The address
override lets container traffic reach the application; the published port still
binds only to the host's loopback interface. Its H2 data remains temporary.

### Local tests

```bash
mvn test
```

Application startup tests select `dev` or the isolated `test` profile and generate
transient JWT keys rather than committing test credentials. The suite needs no PostgreSQL or
OnePipe environment. Provider tests use synthetic settings and mock HTTP
boundaries. Passing tests do not establish the historical OnePipe contract or
repair the limitations above.

## Environment variables

| Variable | Description | Required for |
|:---------|:------------|:-------------|
| `SPRING_PROFILES_ACTIVE` | Exactly one of `dev`, `test`, `hosted`; CLI/IDE selection also works | Startup |
| `JWT_SECRET` | Private Base64 JWT signing key with at least 32 random bytes | Startup; tests generate their own |
| `PORT` | HTTP port; defaults to `8080` | Optional |
| `ONEPIPE_MODE` | `mock` by default; explicit `real` is allowed only in `dev` | Optional |
| `ONEPIPE_BASE_URL` | Provider API base URL | Explicit `dev` real mode |
| `ONEPIPE_API_KEY` | Provider bearer token | Explicit `dev` real mode |
| `ONEPIPE_CLIENT_SECRET` | Provider authorization secret | Explicit `dev` real mode |
| `ONEPIPE_WEBHOOK_URL` | Full provider callback URL | Explicit `dev` real mode |
| `DB_URL` | PostgreSQL JDBC URL with the database's required connection options; no fallback | `hosted` |
| `DB_USERNAME` | Privately supplied database username; no fallback | `hosted` |
| `DB_PASSWORD` | Privately supplied database password; no fallback | `hosted` |
| `DEMO_SEED_ENABLED` | Explicit dev-only opt-in; defaults to `false`; hosted ignores it | Optional |
| `SUPER_ADMIN_EMAIL` | Stable, supplied super-admin identity | Enabled dev seeding |
| `SUPER_ADMIN_PASSWORD` | Supplied private super-admin password; never rotates an existing password | Enabled dev seeding |
| `DEMO_BRANCH_ADMIN_PASSWORD` | Private branch password; must match an existing orphaned account to repair it | Enabled dev seeding |

Do not reuse historical internship credentials. The `hosted` profile fixes the
PostgreSQL driver and validates an existing schema; `DB_DRIVER_CLASS` is not used.
See [hosted prerequisites](docs/configuration.md#hosted-postgresql) before choosing
that profile. An empty database cannot initialize until schema migrations are
implemented in a later task.

## Deployment status

A Dockerfile is provided. Render and Vercel were used for the internship
demonstration; those deployments are historical and their availability has not
been verified. The original hosted PostgreSQL database has expired. No currently
working hosted demo is claimed here.

The `hosted` profile reads a host-provided `PORT`, binds to `0.0.0.0` and uses
environment-backed PostgreSQL settings. This prepares application configuration
for a future hosted mock demo, including Render. No database was provisioned,
deployment changed or hosted connection verified as part of this configuration
work.

## Frontend repository

The React (TypeScript + Vite) frontend source is maintained separately:
[Greenfield Frontend](https://github.com/SirOlash/Greenfield-Boarding-School-Frontend).

### 👨‍💻 Author
Olasupo Emmanuel
- LinkedIn: https://linkedin.com/in/sirolashemmanuel
- GitHub: https://github.com/SirOlash
- Email: Olasupoemmanuel30@gmail.com

