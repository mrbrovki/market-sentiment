FROM maven:3.9.9-eclipse-temurin-23 AS builder
WORKDIR /app
COPY pom.xml ./
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:23-alpine
WORKDIR /app

# install bash for wait-for-it.sh
RUN apk add --no-cache bash

# copy the jar from the previous stage (builder)
COPY --from=builder /app/target/*.jar app.jar


COPY wait-for-it.sh /usr/local/bin/wait-for-it.sh
RUN chmod +x /usr/local/bin/wait-for-it.sh

CMD ["wait-for-it.sh", "firefox:4444", "--", "java", "-jar", "app.jar"]
