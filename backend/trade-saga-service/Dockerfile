FROM eclipse-temurin:25-jre

WORKDIR /app

COPY target/*-exec.jar app.jar

USER 185

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
