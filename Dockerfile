# PR #33 - multi-stage Dockerfile.
# Stage 1 builds the jar with Maven; stage 2 runs it on a slim JRE - the image
# contains ONLY the runtime, never the build toolchain.

# ---- build ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
# Resolve dependencies first (layer caching: only pom changes bust this layer).
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q package -DskipTests

# ---- runtime ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S orderapi && adduser -S orderapi -G orderapi
WORKDIR /app
COPY --from=build /workspace/target/order-management-api-*.jar app.jar
USER orderapi
EXPOSE 8080
# Heap scales with container memory limits (used by the k8s resource requests).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
