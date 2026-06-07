FROM maven:3.9.6-eclipse-temurin-21 AS build
COPY . /app
WORKDIR /app
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jdk-jammy
WORKDIR /app
COPY --from=build /app/target/*.jar ./app.jar
COPY --from=build /app/cabecalho.png ./cabecalho.png
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]