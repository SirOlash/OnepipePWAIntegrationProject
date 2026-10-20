# Greenfield School Payments Demo

![Java](https://img.shields.io/badge/Java-17-orange?style=flat-square&logo=java)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.1.6-brightgreen?style=flat-square&logo=springboot)
![Docker](https://img.shields.io/badge/Docker-Containerized-blue?style=flat-square&logo=docker)

A Java 17 / Spring Boot school-payments prototype originally built for a OnePipe
internship demonstration. It is being improved as a reproducible portfolio demo.
The historical OnePipe credentials and API environment are unavailable, and the
original hosted PostgreSQL database has expired.

The default payment adapter is an offline mock. Its account identifiers are
synthetic: it creates no bank accounts, sends no transfers or debits, and does not
automatically confirm payment. The historical real adapter remains an explicit
opt-in; its presence does not establish a currently working provider integration.

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
            |-- H2 (local in-memory default)
            `-- PostgreSQL (optional persistent configuration)
```

Mock mode makes no OnePipe calls. Only explicit real mode creates the real
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
- Demo seeding is disabled by default. Explicit opt-in requires supplied
  credentials, stores BCrypt password hashes and preserves existing passwords.
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
- H2 is bundled for local use; PostgreSQL is optional

No OnePipe credentials are needed in mock mode. Database/profile separation
remains pending, so these local commands explicitly select the H2 dialect.

### 1. Clone the repository

```bash
git clone https://github.com/SirOlash/OnepipePWAIntegrationProject.git
cd OnepipePWAIntegrationProject
```

### 2. Set environment variables

Set `JWT_SECRET` privately in your shell. Spring Boot does not automatically load
a `.env` file. See [application-example.properties](application-example.properties)
for environment-backed settings. Demo account seeding is disabled by default.

### 3. Run locally with H2

```bash
mvn spring-boot:run -Dspring-boot.run.arguments="--onepipe.mode=mock --spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect --server.address=127.0.0.1"
```

H2 data is in memory and is lost when the application stops. Binding to loopback
keeps this local run on the host machine.

### 4. Run with Docker

```bash
docker build -t greenfield-backend .
docker run -p 127.0.0.1:8080:8080 \
  -e JWT_SECRET \
  greenfield-backend \
  --onepipe.mode=mock \
  --spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect
```

### Local tests

```bash
mvn test
```

Provider tests use synthetic settings and mock HTTP boundaries. Passing tests do
not establish the historical OnePipe contract or repair the limitations above.

## Environment variables

| Variable | Description | Required for |
|:---------|:------------|:-------------|
| `JWT_SECRET` | Private Base64 JWT signing key | Application startup |
| `ONEPIPE_MODE` | `mock` (default) or explicit `real` | Optional |
| `ONEPIPE_BASE_URL` | Provider API base URL | Real mode |
| `ONEPIPE_API_KEY` | Provider bearer token | Real mode |
| `ONEPIPE_CLIENT_SECRET` | Provider authorization secret | Real mode |
| `ONEPIPE_WEBHOOK_URL` | Full provider callback URL | Real mode |
| `DB_URL` | PostgreSQL JDBC URL | PostgreSQL |
| `DB_USERNAME` | Database username | PostgreSQL |
| `DB_PASSWORD` | Database password | PostgreSQL |
| `DB_DRIVER_CLASS` | Set to `org.postgresql.Driver` for PostgreSQL | PostgreSQL |
| `DEMO_SEED_ENABLED` | Explicit opt-in; defaults to `false` | Optional |
| `SUPER_ADMIN_EMAIL` | Supplied super-admin identity | Enabled seeding |
| `SUPER_ADMIN_PASSWORD` | Supplied private super-admin password | Enabled seeding |
| `DEMO_BRANCH_ADMIN_PASSWORD` | Supplied private branch password | Enabled seeding |

Do not reuse historical internship credentials. Separate H2/PostgreSQL profiles
and a persistent hosted-demo configuration remain follow-up work.

## Deployment status

A Dockerfile is provided. Render and Vercel were used for the internship
demonstration; those deployments are historical and their availability has not
been verified. The original hosted PostgreSQL database has expired. No currently
working hosted demo is claimed here.

## Frontend repository

The React (TypeScript + Vite) frontend source is maintained separately:
[Greenfield Frontend](https://github.com/SirOlash/Greenfield-Boarding-School-Frontend).

### 👨‍💻 Author
Olasupo Emmanuel
- LinkedIn: https://linkedin.com/in/sirolashemmanuel
- GitHub: https://github.com/SirOlash
- Email: Olasupoemmanuel30@gmail.com

