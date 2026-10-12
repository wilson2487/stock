# 第一階段：使用 Maven + OpenJDK 17 進行構建打包
FROM maven:3.8.5-openjdk-17 AS build
WORKDIR /app
COPY . .
RUN ./mvnw clean package -DskipTests || mvn clean package -DskipTests

# 第二階段：使用官方推薦的 Eclipse Temurin Java 17 運行環境（替換原本已被廢棄的 openjdk:17-jdk-slim）
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT printenv | grep -E 'RABBITMQ|REDIS' && java -Xmx350m -jar app.jar
