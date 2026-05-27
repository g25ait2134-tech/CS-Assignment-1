FROM eclipse-temurin:21-jdk

WORKDIR /app
COPY src /app/src
RUN javac -d /app/out $(find /app/src -name '*.java')

EXPOSE 8080 8090
CMD ["java", "-cp", "/app/out", "demo.CryptoDemoApp"]
