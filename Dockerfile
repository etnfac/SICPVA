# Estágio 1: Instala o Maven e o Java 21 da Eclipse, copia os arquivos e compila
FROM maven:3.9.6-eclipse-temurin-21 AS build
COPY . /app
WORKDIR /app
RUN mvn clean package -DskipTests

# Estágio 2: Cria o ambiente limpo com o Java 21 para rodar a API
FROM eclipse-temurin:21-jdk-jammy
COPY --from=build /app/target/*.jar /app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]