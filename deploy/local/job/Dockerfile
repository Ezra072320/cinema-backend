ARG RUNTIME_IMAGE=eclipse-temurin:21-jre
FROM xuxueli/xxl-job-admin:3.2.0 AS admin
FROM ${RUNTIME_IMAGE}
WORKDIR /app
COPY --from=admin /app.jar /app/app.jar
ENTRYPOINT ["java","-Xms128m","-Xmx256m","-XX:MaxDirectMemorySize=64m","-XX:ActiveProcessorCount=2","-Duser.timezone=Asia/Shanghai","-jar","/app/app.jar"]
