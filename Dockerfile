FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
ARG SERVICE=cinema-gateway
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 cinema \
    && useradd --uid 10001 --gid cinema --create-home --home-dir /home/cinema --shell /usr/sbin/nologin cinema
COPY --from=build /workspace/${SERVICE}/target/*-exec.jar /app/app.jar
USER cinema
ENV TZ=Asia/Shanghai
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Duser.timezone=Asia/Shanghai"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
