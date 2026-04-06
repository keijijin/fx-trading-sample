FROM registry.access.redhat.com/ubi9/openjdk-21-runtime

WORKDIR /app

COPY target/*-exec.jar app.jar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
