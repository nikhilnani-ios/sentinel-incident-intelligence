# One multi-stage build parameterised by service, so all four images share a base layer and the
# dependency download is cached once for the whole monorepo rather than four times.
FROM maven:3-eclipse-temurin-24 AS deps
WORKDIR /build

COPY pom.xml .
COPY platform-common/pom.xml platform-common/
COPY platform-domain/pom.xml platform-domain/
COPY ingest-service/pom.xml ingest-service/
COPY correlation-service/pom.xml correlation-service/
COPY incident-service/pom.xml incident-service/
COPY insight-service/pom.xml insight-service/

# Fails soft: the goal is a warm local repository, not a complete resolution of every profile.
RUN mvn -B -q dependency:go-offline -DskipTests || true

FROM deps AS build
ARG SERVICE
COPY . .
RUN mvn -B -pl ${SERVICE} -am package -DskipTests

FROM eclipse-temurin:21-jre-jammy AS runtime
ARG SERVICE
WORKDIR /app

# Non-root, and a distinct uid so a host bind mount cannot be written as root by accident.
RUN groupadd --gid 10001 sentinel && useradd --uid 10001 --gid sentinel --shell /bin/false sentinel

COPY --from=build /build/${SERVICE}/target/*.jar app.jar
RUN chown -R sentinel:sentinel /app
USER sentinel

# Container-aware heap sizing beats a hard -Xmx that has to be retuned for every pod size.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseZGC -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=4 \
  CMD wget -qO- http://localhost:8080/actuator/health/readiness | grep -q UP || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
