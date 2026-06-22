# Stage 1 — build
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./
COPY interaction-api/build.gradle interaction-api/
COPY interaction-api/src interaction-api/src
RUN ./gradlew :interaction-api:bootJar --no-daemon

# Stage 2 — runtime + NewRelic agent
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# NewRelic Java agent — downloaded at build time, not committed to git
ADD https://download.newrelic.com/newrelic/java-agent/newrelic-agent/current/newrelic-java.zip /tmp/newrelic-java.zip
RUN unzip /tmp/newrelic-java.zip -d /opt && rm /tmp/newrelic-java.zip

COPY --from=build /workspace/interaction-api/build/libs/interaction-api.jar app.jar

ENV JAVA_TOOL_OPTIONS="-javaagent:/opt/newrelic/newrelic.jar"
ENV NEW_RELIC_APP_NAME="interaction-api"
ENV NEW_RELIC_LOG_LEVEL="info"
# NEW_RELIC_LICENSE_KEY injected at runtime via K8s secret / docker-compose env

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
