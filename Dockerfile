ARG RUNTIME_IMAGE=eclipse-temurin:21-jre
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package

FROM ${RUNTIME_IMAGE} AS runtime-base
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 cinema \
    && useradd --uid 10001 --gid cinema --create-home --home-dir /home/cinema --shell /usr/sbin/nologin cinema
USER cinema
ENV TZ=Asia/Shanghai
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Duser.timezone=Asia/Shanghai"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM runtime-base AS runtime
ARG SERVICE=cinema-gateway
COPY --from=build --chown=cinema:cinema /workspace/${SERVICE}/target/*-exec.jar /app/app.jar
