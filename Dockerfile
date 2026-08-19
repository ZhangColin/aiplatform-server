FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN apk add --no-cache tzdata curl && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime && \
    echo "Asia/Shanghai" > /etc/timezone && \
    apk del tzdata
COPY target/aiplatform-server-1.0.0-SNAPSHOT.jar app.jar
EXPOSE 8888
ENTRYPOINT ["java", "--enable-preview", "-Duser.timezone=Asia/Shanghai", "-jar", "app.jar"]
