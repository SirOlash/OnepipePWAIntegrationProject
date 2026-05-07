# 🏫 Greenfield – Multi-Tenant EdTech Payment Infrastructure

![Java](https://img.shields.io/badge/Java-17-orange?style=flat-square&logo=java)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.1.6-brightgreen?style=flat-square&logo=springboot)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15-blue?style=flat-square&logo=postgresql)
![Docker](https://img.shields.io/badge/Docker-Containerized-blue?style=flat-square&logo=docker)
![OnePipe](https://img.shields.io/badge/OnePipe-PWA%20Integration-purple?style=flat-square)

> A production-grade, multi-tenant payment infrastructure for boarding schools,
> built on top of **OnePipe's PayWithAccount (PWA) API Services**.

---

## 📌 Table of Contents
- [Overview](#overview)
- [Architecture](#architecture)
- [Tech Stack](#tech-stack)
- [Key Features](#key-features)
- [API Endpoints](#api-endpoints)
- [Security](#security)
- [Getting Started](#getting-started)
- [Environment Variables](#environment-variables)
- [Deployment](#deployment)
- [Frontend Repository](#frontend-repository)

---

## 🎯 Overview

Greenfield automates school fee collection for multi-campus boarding schools by
integrating directly with **OnePipe's financial infrastructure**.

Each school branch is onboarded as a **sub-merchant** on OnePipe, and parents
are automatically assigned **Virtual Bank Accounts** or **Mandate-based** payment
plans immediately upon student registration.

The system eliminates manual reconciliation by:
- Generating **Virtual Accounts** for every payment request.
- Processing payments via **Webhooks** in real-time.
- Running **background jobs** to expire stale invoices automatically.

---

## 🏗️ Architecture

```
Client (React Frontend)
        │
        │ HTTPS + JWT
        ▼
Spring Boot REST API
        │
        ├── Auth Service (JWT + RBAC)
        ├── Payment Service (OnePipe Integration)
        ├── Student Service (Registration Flow)
        └── Branch Service (Merchant Onboarding)
                │
                │ HTTPS
                ▼
        OnePipe PWA API
        (Virtual Accounts / Mandates / Webhooks)
                │
                │ Webhook Callback
                ▼
        PostgreSQL Database
        (Users / Branches / Students / Payments)
```

---

## 🛠️ Tech Stack

| Layer                | Technology                                |
|:---------------------|:------------------------------------------|
| **Language**         | Java 17                                   |
| **Framework**        | Spring Boot 3.1.6                         |
| **Security**         | Spring Security + JWT + TripleDES         |
| **Database**         | PostgreSQL (Production), H2 (Development) |
| **ORM**              | Hibernate / Spring Data JPA               |
| **Payment API**      | OnePipe PayWithAccount (PWA)              |
| **Containerization** | Docker                                    |
| **Deployment**       | Render (Backend) + Vercel (Frontend)      |
| **Build Tool**       | Maven                                     |

---

## ✨ Key Features

### 💳 Payment Infrastructure
- **Virtual Account Generation:** Instantly creates unique bank accounts per
  payment request via OnePipe.
- **Three Payment Models:**
    - `SINGLE_PAYMENT` – Ad-hoc invoice with a time-limited Virtual Account.
    - `INSTALLMENT` – 20% down payment + recurring cycles (Weekly/Monthly).
    - `SUBSCRIPTION` – Auto-debit recurring payments for the school term.
- **QR Code Support:** Returns a scannable QR Code from OnePipe for instant payment.

### 🔐 Security
- **JWT Authentication:** Stateless, role-based access control.
- **TripleDES Encryption:** Banking payload encryption for mandate creation.
- **HMAC Webhook Verification:** Cryptographic signature validation on all
  incoming OnePipe callbacks.
- **Idempotency:** Duplicate webhook protection using `transactionRef` as
  the idempotency key.

### 🏢 Multi-Tenancy
- **Branch-as-Merchant Model:** Each school campus is onboarded as a separate
  OnePipe sub-merchant with its own `billerCode` and settlement account.
- **Role-Based Dashboards:** `SUPER_ADMIN`, `BRANCH_ADMIN`, and `PARENT` roles
  each see different data.

### ⚙️ Reliability
- **Webhook Processing:** Real-time payment status updates via OnePipe callbacks.
- **Background Jobs (`@Scheduled`):** Automatically expires stale invoices by
  comparing `virtualAccountExpiryDate` with the current time.
- **Query API:** Fallback mechanism to manually verify payment status if a
  webhook is missed.

---

## 📡 API Endpoints

### Authentication
| Method | Endpoint          | Access | Description         |
|:-------|:------------------|:-------|:--------------------|
| `POST` | `/api/auth/login` | Public | Login for all roles |

### Branch Management
| Method | Endpoint        | Access      | Description                  |
|:-------|:----------------|:------------|:-----------------------------|
| `POST` | `/api/branches` | SUPER_ADMIN | Create a new branch/merchant |
| `GET`  | `/api/branches` | Public      | Get all branches             |

### Student Management
| Method | Endpoint                            | Access       | Description                          |
|:-------|:------------------------------------|:-------------|:-------------------------------------|
| `POST` | `/api/students/register`            | Public       | Register a student + trigger payment |
| `GET`  | `/api/students?branchId={id}`       | BRANCH_ADMIN | Get students by branch               |
| `GET`  | `/api/students?parentEmail={email}` | PARENT       | Get children by parent               |

### Payments
| Method | Endpoint                           | Access       | Description                       |
|:-------|:-----------------------------------|:-------------|:----------------------------------|
| `POST` | `/api/payments/new`                | BRANCH_ADMIN | Trigger ad-hoc payment            |
| `GET`  | `/api/payments?branchId={id}`      | BRANCH_ADMIN | Get payments by branch            |
| `GET`  | `/api/payments?studentId={id}`     | PARENT       | Get payments by student           |
| `POST` | `/api/payments/{id}/cancel`        | ADMIN/PARENT | Cancel a subscription             |
| `POST` | `/api/payments/{id}/query`         | ADMIN        | Query payment status from OnePipe |
| `PUT`  | `/api/payments/students/{id}/plan` | PARENT       | Switch payment plan               |

### Webhooks
| Method | Endpoint                | Access | Description                       |
|:-------|:------------------------|:-------|:----------------------------------|
| `POST` | `/api/webhooks/onepipe` | Public | OnePipe transaction notifications |

### Dashboard
| Method | Endpoint                 | Access      | Description              |
|:-------|:-------------------------|:------------|:-------------------------|
| `GET`  | `/api/super-admin/stats` | SUPER_ADMIN | Platform-wide statistics |

---

## 🔒 Security Design

```
Every Request
    │
    ├── 1. JWT Filter        → Validates Token & Loads User
    ├── 2. RBAC              → Checks Role per Endpoint
    ├── 3. TripleDES         → Encrypts Bank Details
    ├── 4. HMAC Verification → Validates Webhook Signatures
    └── 5. Idempotency Keys  → Prevents Duplicate Processing
```

---

## 🚀 Getting Started

### Prerequisites
- Java 17+
- Maven 3.9+
- PostgreSQL (or Docker)
- OnePipe API Credentials

### 1. Clone the Repository
```bash
git clone https://github.com/SirOlash/OnepipePWAIntegrationProject.git
cd OnepipePWAIntegrationProject
```

### 2. Set Environment Variables
Create a .env file or set the following in your system environment.
(See Environment Variables section below).

### 3. Run Locally (H2 in-memory DB)
``` bash
mvn spring-boot:run
```
### 4. Run with Docker
```bash
docker build -t greenfield-backend .
docker run -p 8080:8080 \
  -e JWT_SECRET=your_secret \
  -e ONEPIPE_API_KEY=your_key \
  -e ONEPIPE_CLIENT_SECRET=your_secret \
  greenfield-backend
```

### 🔑 Environment Variables
| Variable                | Description                            | Required   |
|:------------------------|:---------------------------------------|:-----------|
| `DB_URL`                | PostgreSQL JDBC URL                    | Production |
| `DB_USERNAME`           | Database username                      | Production |
| `DB_PASSWORD`           | Database password                      | Production |
| `JWT_SECRET`            | Base64 encoded JWT signing key         | Yes        |
| `ONEPIPE_API_KEY`       | OnePipe Bearer Token                   | Yes        |
| `ONEPIPE_CLIENT_SECRET` | OnePipe Client Secret (for encryption) | Yes        |
| `SUPER_ADMIN_EMAIL`     | Default Super Admin email              | Optional   |

### 🐳 Deployment
This project is containerized with Docker and deployed on Render.

Live Backend URL:
https://greenfield-backend-lkse.onrender.com

### 🖥️ Frontend Repository
The React (TypeScript + Vite) frontend is maintained in a separate repository.
Frontend Repo: [Greenfield Frontend[](https://github.com/SirOlash/Greenfield-Boarding-School-Frontend)]
Live Demo: https://greenfieldboardingschool.vercel.app

### 👨‍💻 Author
Olasupo Emmanuel
- LinkedIn: linkedin.com/in/sirolashemmanuel
- GitHub: github.com/SirOlash
- Email: Olasupoemmanuel30@gmail.com

### 📄 License
This project is open source and available under the MIT License.

