ARG RUNTIME_IMAGE=eclipse-temurin:21-jre
FROM ${RUNTIME_IMAGE}
WORKDIR /app
ADD --chmod=0444 --checksum=sha256:7b5ddee69f9e0593088995db22043de234a3bc60739d772d5fb5bf8447670cab https://github.com/alibaba/Sentinel/releases/download/1.8.9/sentinel-dashboard-1.8.9.jar /app/app.jar
ENTRYPOINT ["java","-Xms96m","-Xmx256m","-XX:MaxDirectMemorySize=64m","-XX:ActiveProcessorCount=2","-Dserver.port=8080","-Dproject.name=sentinel-dashboard","-Duser.timezone=Asia/Shanghai","-jar","/app/app.jar"]
