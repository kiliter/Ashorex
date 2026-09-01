# syntax=docker/dockerfile:1
# 先由 make verify 生成并验证 JAR；镜像层不接收任何生产秘密。
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends sqlite3 curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 shangan \
    && useradd --system --uid 10001 --gid shangan --home-dir /app --shell /usr/sbin/nologin shangan
WORKDIR /app
COPY apps/server/target/shangan-server-*.jar /app/server.jar
COPY infra/scripts /app/infra/scripts
RUN mkdir -p /data /backup \
    && chmod +x /app/infra/scripts/*.sh \
    && chown -R shangan:shangan /app /data /backup
USER 10001:10001
VOLUME ["/data", "/backup"]
EXPOSE 18080
ENV DATA_DIR=/data \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8"
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl --fail --silent http://127.0.0.1:18080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/server.jar"]
