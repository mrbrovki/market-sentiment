FROM maven:3.9.9-eclipse-temurin-23 AS builder
WORKDIR /app
COPY pom.xml ./
COPY src ./src
RUN mvn clean package -DskipTests
FROM eclipse-temurin:23-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
CMD ["java", "-jar", "-Xms512m", "-Xmx4g", "app.jar"]
