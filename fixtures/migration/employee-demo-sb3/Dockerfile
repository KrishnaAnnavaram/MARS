# MIGRATION-DEMO: Java 17 base image
# During Spring Boot 4 migration, this will be updated to Java 21
# This demonstrates the JDK version upgrade path
FROM eclipse-temurin:17-jre

# Set working directory
WORKDIR /app

# Copy the built JAR file
COPY target/spring-boot-migration-demo-1.0.0.jar app.jar

# Expose the application port
EXPOSE 8080

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
