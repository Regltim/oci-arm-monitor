FROM maven:3.9.11-eclipse-temurin-17 AS builder

WORKDIR /workspace

COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:17-jre-jammy

ARG APP_UID=10001
ARG APP_GID=10001

RUN groupadd --gid ${APP_GID} monitor \
  && useradd --uid ${APP_UID} --gid ${APP_GID} --create-home --shell /usr/sbin/nologin monitor \
  && mkdir -p /app /data /home/monitor/.oci \
  && chown -R monitor:monitor /app /data /home/monitor

WORKDIR /app

COPY --from=builder /workspace/target/oci-arm-cost-monitor-*.jar /app/app.jar

ENV OCI_MONITOR_DB=/data/oci-arm-cost-monitor.db
ENV OCI_CONFIG_FILE_PATH=/home/monitor/.oci/config
ENV OCI_CONFIG_PROFILE=DEFAULT

EXPOSE 9090

USER monitor

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
