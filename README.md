# OrderFlow Order Service

Spring Boot application that exposes the public order API and orchestrates the OrderFlow saga.

## F02 foundation

- Java 17 and Maven Wrapper.
- Spring Boot Web MVC, Validation, Actuator, Data JPA, PostgreSQL, Flyway, and Kafka client.
- Owned PostgreSQL database configured only through environment variables.
- Liveness at `/actuator/health/liveness` and readiness at `/actuator/health/readiness`.
- Unit context test with H2 and PostgreSQL migration integration test with Testcontainers.

## F03 order creation

`POST /api/v1/orders` requires a Keycloak access token with the `CUSTOMER` realm role and an
`Idempotency-Key` header. The complete request, response, and error contract is documented in
[`docs/openapi.yaml`](docs/openapi.yaml).

Order creation validates the owned product snapshot, consolidates repeated products, snapshots BRL
prices, calculates the total, and commits the order, initial history entry, and
`InventoryReservationRequested.v1` outbox intent atomically. Publishing the outbox is intentionally
deferred to F05.

The development snapshot contains two active products and one inactive product:

| Product ID | Product | Price | Active |
| --- | --- | ---: | --- |
| `22222222-2222-4222-8222-222222222221` | Suporte para notebook | BRL 149.90 | yes |
| `22222222-2222-4222-8222-222222222222` | Teclado mecânico | BRL 399.00 | yes |
| `22222222-2222-4222-8222-222222222223` | Mouse descontinuado | BRL 89.90 | no |

## Build and test

```powershell
./mvnw.cmd clean verify
```

The `verify` phase requires a Docker-compatible runtime for the Testcontainers test. Local runtime variables and orchestration commands are documented in `orderflow-platform`.

