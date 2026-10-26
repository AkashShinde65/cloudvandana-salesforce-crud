# Stage 1: Build React frontend
FROM node:24-alpine AS frontend-build
WORKDIR /frontend

COPY frontend/package*.json ./
RUN npm ci

COPY frontend/ ./
RUN npm run build

# Stage 2: Build Spring Boot backend
FROM maven:3.9-eclipse-temurin-21 AS backend-build
WORKDIR /app

COPY pom.xml ./
COPY .mvn/ .mvn/
COPY mvnw ./
RUN chmod +x mvnw

COPY src/ src/

# Package the built React files with Spring Boot
COPY --from=frontend-build /frontend/dist/ /app/src/main/resources/static/

RUN ./mvnw -B clean package -DskipTests

# Stage 3: Run the combined application
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=backend-build /app/target/*.jar /app/app.jar

ENTRYPOINT ["sh", "-c", "java -Dserver.port=${PORT:-10000} -jar /app/app.jar"]
