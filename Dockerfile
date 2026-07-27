FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline
COPY src src
RUN mvn -B verify

FROM eclipse-temurin:17-jre
RUN addgroup --system spring && adduser --system --ingroup spring spring
USER spring:spring
WORKDIR /app
COPY --from=build /workspace/target/resilient-ha-oci-cache-*.jar app.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
