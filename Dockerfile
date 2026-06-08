FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn clean package -DskipTests -q

FROM eclipse-temurin:25-jdk
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENV JAVA_OPTS="-Xmx320m -XX:+UseSerialGC -XX:MaxRAMPercentage=70"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]