# Spring Boot 3 to 4 Migration Demo

## Purpose

This project demonstrates a **Spring Boot 3.5.x baseline** application that is intentionally architected to showcase the migration path from Spring Boot 3 to Spring Boot 4. The application includes realistic components, dependencies, and configurations that will require validation or changes during the migration process.

The goal is **NOT** to show breaking changes, but rather to demonstrate:

- Platform modernization
- Version compatibility validation
- Ecosystem changes in the Spring Boot 4 environment
- How business functionality remains unchanged across the migration

## Current Baseline

```
Spring Boot Version:          3.5.0
Spring Framework Version:     6.x
Java Version:                 17
Spring Security Version:      6 (Spring Security 6.x)
Hibernate Version:            6.x
Jakarta Persistence:          6.0+
Jackson Version:              2.x (MIGRATION-DEMO)
Tomcat Version:               10.x
H2 Database:                  Boot 3 managed version
PostgreSQL JDBC Driver:       Test scope (Testcontainers)
Build Tool:                   Maven
JPA Implementation:           Hibernate with Spring Data JPA
ORM Approach:                 Jakarta Persistence with JPQL and Native Queries
```

## Architecture

The project follows a layered architecture:

```
Controller Layer          → REST endpoints with validation and error handling
                ↓
Service Layer             → Business logic with transaction boundaries
                ↓
Repository Layer          → Data access with Spring Data JPA
                ↓
Persistence Layer         → File-based H2 database with Hibernate
                ↓
Configuration             → Security, Jackson, Actuator, DataSource
```

## Technology Stack

### Core Framework

- **Spring Boot 3.5.x** (parent)
- **Spring Framework 6.x**
- **Spring Data JPA** with Hibernate 6.x
- **Spring Security 6** (for HTTP Basic auth)
- **Spring Boot Actuator** (for health checks and metrics)

### Data Access

- **Hibernate 6.x** ORM
- **Jakarta Persistence** (jakarta.persistence.\*)
- **H2 2.x** file-based database for local runtime
- **PostgreSQL 16** via Testcontainers for integration testing

### Serialization

- **Jackson 2.x** (com.fasterxml.jackson.\*)
- **Custom JacksonConfig** demonstrating Boot 3 / Jackson 2 APIs

### Testing

- **JUnit 5** with Mockito
- **Spring Boot Test** (MockMvc for controller tests)
- **Testcontainers** with PostgreSQL container for integration testing

### Deployment

- **Docker** with Java 17 base image (eclipse-temurin:17-jre)
- **Docker Compose** definition for an optional standalone PostgreSQL service

## Prerequisites

### Local Development

- Java 17+ installed (JDK)
- Maven 3.8.1+
- Docker or Docker Desktop (only for PostgreSQL Testcontainers integration tests)

### Verify Java Installation

```bash
java -version
javac -version
```

### Verify Maven Installation

```bash
mvn -version
```

## Database Configuration

The application uses a persistent, embedded H2 database by default. No external
database service is required.

- JDBC URL: `jdbc:h2:file:./data/employee_db`
- Username: `sa`
- Password: empty
- Data files: `./data/`

The database schema is created or updated automatically by Hibernate.

### PostgreSQL Integration Tests

PostgreSQL remains available for repository integration tests through
Testcontainers. Docker must be running; Testcontainers starts and removes the
database container automatically.

```bash
mvn test -Dtest=EmployeeRepositoryIntegrationTest
```

## Building the Application

### Build with Tests

```bash
mvn clean verify
```

This command:

- Cleans the previous build
- Compiles source code
- Runs unit tests
- Runs integration tests (with Testcontainers)
- Verifies the build

### Build Without Tests (Faster)

```bash
mvn clean package -DskipTests
```

### View Dependency Tree (Important for Migration)

```bash
# MIGRATION-DEMO: This shows Spring Boot managed versions
mvn dependency:tree | head -50
```

## Running the Application

### Option 1: Maven Spring Boot Plugin

```bash
mvn spring-boot:run
```

The application will start on `http://localhost:8080`

### Option 2: Java Command (After Building)

```bash
java -jar target/spring-boot-migration-demo-1.0.0.jar
```

### Verify Application is Running

```bash
curl http://localhost:8080/actuator/health
```

Expected response:

```json
{
  "status": "UP",
  "components": {
    "database": {
      "status": "UP",
      "details": {
        "database": "H2",
        "status": "Connected"
      }
    },
    "diskSpace": { ... },
    "livenessState": { ... },
    "readinessState": { ... }
  }
}
```

## Authentication

The application uses **HTTP Basic Authentication** with in-memory user:

```
Username: demo
Password: demo123
Role:    USER
```

### Example Authenticated Request

```bash
curl -u demo:demo123 http://localhost:8080/api/v1/employees
```

## API Endpoints

All `/api/**` endpoints require authentication.

### Create Employee

```http
POST /api/v1/employees
Content-Type: application/json
Authorization: Basic demo:demo123

{
  "employeeNumber": "EMP001",
  "firstName": "John",
  "lastName": "Doe",
  "email": "john@example.com",
  "department": "Technology",
  "salary": 95000.00
}
```

Response: `201 Created`

### List All Employees

```http
GET /api/v1/employees
Authorization: Basic demo:demo123
```

Response: `200 OK`

### Get Single Employee

```http
GET /api/v1/employees/{id}
Authorization: Basic demo:demo123
```

Response: `200 OK` or `404 Not Found`

### Update Employee

```http
PUT /api/v1/employees/{id}
Content-Type: application/json
Authorization: Basic demo:demo123

{
  "firstName": "Jane",
  "salary": 98000.00,
  "active": true
}
```

Response: `200 OK`

### Delete Employee

```http
DELETE /api/v1/employees/{id}
Authorization: Basic demo:demo123
```

Response: `204 No Content`

### Search by Department (JPQL Query)

```http
GET /api/v1/employees/search?department=Technology
Authorization: Basic demo:demo123
```

Response: `200 OK` - Returns matching employees

### Find High Earners (Native SQL Query)

```http
GET /api/v1/employees/high-earners?salary=90000
Authorization: Basic demo:demo123
```

Response: `200 OK` - Returns employees with salary > 90000

## Example Requests

### Using curl

```bash
# Create employee
curl -X POST http://localhost:8080/api/v1/employees \
  -H "Content-Type: application/json" \
  -u demo:demo123 \
  -d '{
    "employeeNumber": "EMP010",
    "firstName": "Alice",
    "lastName": "Cooper",
    "email": "alice@example.com",
    "department": "Technology",
    "salary": 100000.00
  }'

# Get all employees
curl -u demo:demo123 http://localhost:8080/api/v1/employees

# Search by department
curl -u demo:demo123 "http://localhost:8080/api/v1/employees/search?department=Technology"

# High earners
curl -u demo:demo123 "http://localhost:8080/api/v1/employees/high-earners?salary=85000"
```

### Using Postman

1. Create a new request collection
2. Set Authorization type to "Basic Auth"
3. Enter username: `demo` and password: `demo123`
4. Use the endpoints listed above

## Running Tests

### Run All Tests

```bash
mvn test
```

### Run Specific Test Class

```bash
mvn test -Dtest=EmployeeMapperTest
mvn test -Dtest=EmployeeServiceTest
mvn test -Dtest=EmployeeControllerTest
mvn test -Dtest=EmployeeRepositoryIntegrationTest
```

### Run Tests with Coverage Report

```bash
mvn test jacoco:report
```

### Test Results

```
Unit Tests:
  - EmployeeMapperTest
  - EmployeeServiceTest

Controller Tests:
  - EmployeeControllerTest (MockMvc, authentication scenarios)

Integration Tests:
  - EmployeeRepositoryIntegrationTest (Testcontainers with PostgreSQL)
```

## Migration Areas to Demonstrate

### 1. **Spring Boot Version Upgrade** (3.5.x → 4.x)

- **Current:** Spring Boot 3.5.x in `pom.xml` parent
- **Future:** Spring Boot 4.x
- **Validation:** Parent version change, then verify dependency compatibility

### 2. **Java Version Upgrade** (17 → 21)

- **Current:** Java 17 in `pom.xml` properties and `Dockerfile`
- **Future:** Java 21
- **Validation:** Update `<java.version>21</java.version>` and Docker image, recompile

### 3. **Spring Framework Version** (6.x → 7.x)

- **Current:** Spring Framework 6.x (managed by Boot 3 parent)
- **Future:** Spring Framework 7.x (managed by Boot 4 parent)
- **Validation:** Removed/deprecated API checks, see `config/SecurityConfig.java`

### 4. **Spring Security Version** (6.x → 7.x)

- **Location:** `config/SecurityConfig.java`
- **Current:** Spring Security 6.x API (SecurityFilterChain, authorizeHttpRequests)
- **Future:** Spring Security 7.x
- **Migration Note:** API is stable; validation ensures no deprecated methods

### 5. **Hibernate ORM Version** (6.x → 7.x)

- **Current:** Hibernate 6.x (managed by Spring Boot 3)
- **Validation Areas:** `repository/EmployeeRepository.java`
  - JPQL query: `findActiveEmployeesByDepartment()` with string interpolation
  - Native SQL query: `findHighEarners()` database compatibility
- **Future:** Hibernate 7.x compatibility

### 6. **Jakarta Persistence** (6.0+ → Latest)

- **Current:** `jakarta.persistence.*` in `entity/Employee.java`
- **Package:** Already using Jakarta (not legacy javax.persistence)
- **Validation:** Entity annotations, @GeneratedValue, @PrePersist, @PreUpdate

### 7. **Jackson Serialization** (2.x → 3.x) 🔴 MAJOR CHANGE

- **Location:** `config/JacksonConfig.java`
- **Current:** `com.fasterxml.jackson.databind.ObjectMapper` (Jackson 2)
- **Future:** Jackson 3.x with breaking API changes
- **Changes:**
  - Package names will change (still com.fasterxml but different organization)
  - Some deprecated APIs will be removed
  - Configuration methods may be renamed
- **Validation:** Update Jackson version, verify ObjectMapper creation and configuration

### 8. **Database Drivers**

- **Default runtime:** H2 with a file-based database
- **Integration tests:** PostgreSQL JDBC driver and PostgreSQL Testcontainers module
- **Current:** Driver versions managed by the Spring Boot 3 parent
- **Future:** Driver versions managed by the Spring Boot 4 parent
- **Application Configuration:** `application.yml` datasource settings
- **Validation:** Verify H2 runtime behavior and PostgreSQL integration tests

### 9. **Web Framework** (Spring Web Starter)

- **Location:** `pom.xml` and `controller/EmployeeController.java`
- **Current:** Spring Boot 3 web starter + Tomcat 10.x
- **Future:** Spring Boot 4 may have different Servlet/Tomcat structure
- **Validation:** REST endpoints continue to work as expected

### 10. **Tomcat Servlet Container** (10.x → 11.x)

- **Current:** Tomcat 10.x (managed by Spring Boot 3)
- **Future:** Tomcat 11.x (managed by Spring Boot 4)
- **Impact:** Usually transparent; tested through integration tests

### 11. **Actuator Health Endpoints**

- **Location:** `health/DatabaseHealthIndicator.java`
- **Current:** Custom health indicator with Boot 3 Actuator
- **Validation:** Ensure `/actuator/health` continues to work

### 12. **Testing Framework** (JUnit 5 + Testcontainers)

- **Current:** JUnit 5 + Mockito + Testcontainers
- **Future:** Spring Boot 4 test stack
- **Validation:** Unit, controller, and integration tests must pass

---

## Spring Boot 3 → 4 Migration Roadmap

| Area                 | Current Demo            | Future Target    | Migration Consideration                       |
| -------------------- | ----------------------- | ---------------- | --------------------------------------------- |
| **Spring Boot**      | 3.5.x                   | 4.x              | Major platform upgrade                        |
| **Java**             | 17                      | 21               | JDK, CI/CD pipelines, Docker images           |
| **Spring Framework** | 6.x                     | 7.x              | Removed/deprecated APIs                       |
| **Spring Security**  | 6.x                     | 7.x              | Security authentication/authorization         |
| **Hibernate ORM**    | 6.x                     | 7.x              | JPQL queries, native queries, entity mappings |
| **Jackson**          | 2.x                     | 3.x              | JSON serialization, ObjectMapper API changes  |
| **Tomcat**           | 10.x                    | 11.x             | Servlet compatibility                         |
| **Database Drivers** | H2 + PostgreSQL tests   | Boot 4 managed   | Runtime and integration-test compatibility    |
| **Web Starter**      | spring-boot-starter-web | Boot 4 variant   | Controller, REST endpoint structure           |
| **Tests**            | Boot 3 + JUnit 5        | Boot 4 + JUnit 5 | Test compatibility                            |

---

## Management Demo Flow

This section describes the intended presentation sequence to demonstrate the migration.

### Phase 1: Demonstrate Current Baseline (Boot 3 Working)

1. **Build the Application**

   ```bash
   mvn clean package -DskipTests
   ```

   H2 is embedded, so no database service needs to be started.

2. **Start the Application**

   ```bash
   mvn spring-boot:run
   ```

3. **Create an Employee**

   ```bash
   curl -X POST http://localhost:8080/api/v1/employees \
     -H "Content-Type: application/json" \
     -u demo:demo123 \
     -d '{
       "employeeNumber": "EMP001",
       "firstName": "John",
       "lastName": "Smith",
       "email": "john.smith@example.com",
       "department": "Technology",
       "salary": 95000.00
     }'
   ```

4. **Retrieve the Employee**

   ```bash
   curl -u demo:demo123 http://localhost:8080/api/v1/employees/1
   ```

5. **Show Current Versions**

   ```bash
   java -version
   # Output: openjdk version "17.x.x" ...

   mvn -v
   # Output: Apache Maven 3.8.x ...
   ```

6. **Show Spring Boot and Dependency Versions**

   ```bash
   mvn dependency:tree | grep "spring-boot-starter-\|h2\|postgresql\|jackson\|hibernate"
   ```

   - Spring Boot: 3.5.0
   - H2: Boot 3 managed version
   - PostgreSQL JDBC: test scope
   - Jackson: 2.x
   - Hibernate: 6.x

7. **Check Actuator Health**
   ```bash
   curl http://localhost:8080/actuator/health | jq .
   ```

   - Status: UP
   - Database: UP
   - All components healthy ✅

---

### Phase 2: Migration Preparation (Change Dependencies)

9. **Stop Application**

   ```bash
   Ctrl+C
   ```

10. **Update Spring Boot Version (3.5.x → 4.x)**
    - Modify `pom.xml`: Change `spring-boot-starter-parent` version from `3.5.0` to `4.0.0` (or latest 4.x)

11. **Update Java Version (17 → 21)**
    - Modify `pom.xml`: Change `<java.version>17</java.version>` to `<java.version>21</java.version>`
    - Modify `Dockerfile`: Change `FROM eclipse-temurin:17-jre` to `FROM eclipse-temurin:21-jre`

12. **Attempt to Build**
    ```bash
    mvn clean compile
    ```

    - Expected: Compilation errors or warnings related to:
      - Jackson API changes (if Jackson 3.x is included)
      - Spring Security API changes (if minor breaking changes)
      - Hibernate changes (if new version has incompatibilities)

---

### Phase 3: Address Migration Issues

13. **Fix Compilation Errors**
    - Update `config/JacksonConfig.java` to use Jackson 3.x APIs
    - Update `config/SecurityConfig.java` if Spring Security 7 has API changes
    - Update `repository/EmployeeRepository.java` if Hibernate has query syntax issues

14. **Update Tests**
    - Verify `EmployeeRepositoryIntegrationTest` still works with Testcontainers
    - Update any test utilities if Spring Boot test utilities changed

15. **Build Again**
    ```bash
    mvn clean verify
    ```

    - All tests must pass
    - Zero compilation errors

---

### Phase 4: Validate Migrated Application

16. **Rebuild Application**

    ```bash
    mvn clean package
    ```

17. **Start Migrated Application**

    ```bash
    mvn spring-boot:run
    ```

18. **Verify Same API Endpoint Works**

    ```bash
    curl -u demo:demo123 http://localhost:8080/api/v1/employees/1
    ```

    - Returns: Same employee data as before ✅
    - Business logic unchanged ✅

19. **Verify Actuator Health**

    ```bash
    curl http://localhost:8080/actuator/health | jq .
    ```

    - Status: UP ✅

20. **Run All Tests One More Time**
    ```bash
    mvn test
    ```

    - All tests pass ✅

---

### Key Message to Management

> **The goal of the migration is NOT to change business functionality.**
>
> **The goal is to modernize the technology platform while proving that existing business behavior remains unchanged.**

> **Spring Boot 4 migration is an ecosystem upgrade, not merely a version-number change.**
>
> - Dependencies are managed by the new parent POM
> - Framework APIs may change (Spring Framework 7.x)
> - Database libraries are auto-managed (H2 and PostgreSQL drivers)
> - JSON serialization framework will change (Jackson 3.x)
> - Security patterns will be validated (Spring Security 7.x)
>
> **The employee management API works exactly the same before and after the migration.**

---

## Building Docker Image

### Build the JAR First

```bash
mvn clean package
```

### Build Docker Image

```bash
docker build -t spring-boot-migration-demo:1.0.0 .
```

The application uses H2 by default when the image is started. Mount a volume at
the application working directory if the H2 data must persist outside the
container.

---

## Stopping Services

### Stop Application

```bash
Ctrl+C
```

---

## Troubleshooting

### H2 Database Lock

- Stop any other application process using `./data/employee_db`
- Start the application again

### Unauthorized Errors (401)

- Use correct credentials: `-u demo:demo123`
- Or use: `Authorization: Basic ZGVtbzpkZW1vMTIz` header

### Test Failures

- Ensure Docker is running for PostgreSQL Testcontainers integration tests
- Check logs: `mvn test -X` for debug output

### Maven Build Failures

- Clear Maven cache: `rm -rf ~/.m2/repository`
- Try again: `mvn clean install`

---

## Project Structure

```
spring-boot-3-to-4-migration-demo/
├── pom.xml                                    # Maven configuration (MIGRATION-DEMO markers)
├── Dockerfile                                 # Docker image definition (Java 17)
├── docker-compose.yml                         # Optional standalone PostgreSQL service
├── README.md                                  # This file
├── .gitignore                                 # Git ignore patterns
│
├── src/main/java/com/example/migrationdemo/
│   ├── MigrationDemoApplication.java           # Spring Boot entry point
│   │
│   ├── entity/
│   │   └── Employee.java                       # JPA entity with @PrePersist/@PreUpdate
│   │
│   ├── dto/
│   │   ├── EmployeeCreateRequest.java          # Request DTO with validation
│   │   ├── EmployeeUpdateRequest.java          # Update DTO with optional fields
│   │   └── EmployeeResponse.java               # Response DTO
│   │
│   ├── mapper/
│   │   └── EmployeeMapper.java                 # DTO ↔ Entity mapping
│   │
│   ├── repository/
│   │   └── EmployeeRepository.java             # Spring Data JPA (JPQL + Native queries)
│   │
│   ├── service/
│   │   └── EmployeeService.java                # Business logic with @Transactional
│   │
│   ├── controller/
│   │   └── EmployeeController.java             # REST endpoints
│   │
│   ├── exception/
│   │   ├── EmployeeNotFoundException.java      # Custom exception
│   │   ├── DuplicateEmployeeException.java     # Custom exception
│   │   ├── ErrorResponse.java                  # Error DTO (MIGRATION-DEMO Jackson config)
│   │   └── GlobalExceptionHandler.java         # Central exception handling
│   │
│   ├── config/
│   │   ├── JacksonConfig.java                  # Jackson 2 configuration (MIGRATION-DEMO)
│   │   └── SecurityConfig.java                 # Spring Security 6 config (MIGRATION-DEMO)
│   │
│   ├── health/
│   │   └── DatabaseHealthIndicator.java        # Custom Actuator health check
│   │
│   └── init/
│       └── DataInitializer.java                # Sample data initialization
│
├── src/main/resources/
│   └── application.yml                         # Application configuration
│
├── src/test/java/com/example/migrationdemo/
│   ├── mapper/
│   │   └── EmployeeMapperTest.java             # Unit test
│   │
│   ├── service/
│   │   └── EmployeeServiceTest.java            # Unit test with Mockito
│   │
│   ├── controller/
│   │   └── EmployeeControllerTest.java         # Controller test with MockMvc
│   │
│   ├── integration/
│   │   └── EmployeeRepositoryIntegrationTest.java  # Integration test (Testcontainers)
│   │
│   └── AbstractIntegrationTest.java            # Base test class
│
└── src/test/resources/
    └── application-test.yml                    # Test configuration
```

---

## Next Steps (For Management)

1. ✅ **Review this Spring Boot 3 baseline** - Understand current architecture
2. 📋 **Create Git branches:**
   - `01-spring-boot-3-baseline` (current)
   - `02-spring-boot-4-migration` (WIP)
   - `03-spring-boot-4-completed` (target)
3. 🔄 **Plan migration phases:**
   - Phase 1: Update dependencies (Spring Boot 3.5.x → 4.x, Java 17 → 21)
   - Phase 2: Fix compilation errors (Jackson, Security, Hibernate)
   - Phase 3: Run tests and validate
   - Phase 4: Deployment and monitoring
4. 📊 **Measure:**
   - Build time
   - Test execution time
   - Runtime performance (memory, CPU)
   - API response times

---

## Additional Resources

- [Spring Boot 3.5.x Documentation](https://spring.io/projects/spring-boot)
- [Spring Framework 6.x Documentation](https://spring.io/projects/spring-framework)
- [Hibernate ORM Documentation](https://hibernate.org/)
- [Spring Security Documentation](https://spring.io/projects/spring-security)
- [Jackson Documentation](https://github.com/FasterXML/jackson)
- [H2 Database Documentation](https://www.h2database.com/)
- [PostgreSQL JDBC Driver](https://jdbc.postgresql.org/)
- [Testcontainers Documentation](https://www.testcontainers.org/)

---

**Project Created:** September 2026  
**Spring Boot Version:** 3.5.0  
**Java Version:** 17  
**Status:** ✅ Fully functional baseline ready for migration to Spring Boot 4
