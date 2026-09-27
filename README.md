# OrderFlow Order Service

Spring Boot application that will expose the public order API and orchestrate the OrderFlow saga.

## F02 foundation

- Java 17 and Maven Wrapper.
- Spring Boot Web MVC, Validation, Actuator, Data JPA, PostgreSQL, Flyway, and Kafka client.
- Owned PostgreSQL database configured only through environment variables.
- Liveness at `/actuator/health/liveness` and readiness at `/actuator/health/readiness`.
- Unit context test with H2 and PostgreSQL migration integration test with Testcontainers.

No order endpoint, saga behavior, or Kafka publication is implemented in F02.

## Build and test

```powershell
./mvnw.cmd clean verify
```

The `verify` phase requires a Docker-compatible runtime for the Testcontainers test. Local runtime variables and orchestration commands are documented in `orderflow-platform`.

