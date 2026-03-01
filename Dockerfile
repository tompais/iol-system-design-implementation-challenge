# Stage 1: build — full JDK required for Gradle compilation
FROM eclipse-temurin:24-jdk AS builder
WORKDIR /build
COPY . .
RUN ./gradlew bootJar --no-daemon -x test

# Stage 2: runtime — JRE only (no compiler, no Gradle, ~200 MB vs ~500 MB)
FROM eclipse-temurin:24-jre AS runtime
WORKDIR /app
COPY --from=builder /build/build/libs/*.jar app.jar

# ZGC: sub-millisecond GC pauses — essential for reactive/low-latency workloads
# Xmx256m: leaves ~700 MB for the Grafana LGTM stack on a t2.micro (1 GB total)
# MaxDirectMemorySize: Netty uses off-heap direct buffers for network I/O;
#   without an explicit cap the JVM defaults to Xmx, effectively double-counting memory
# ExitOnOutOfMemoryError: crash-fast rather than limping — orchestrators restart cleanly
ENV JAVA_OPTS="-XX:+UseZGC -Xmx256m -XX:MaxDirectMemorySize=128m -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
EXPOSE 8080
