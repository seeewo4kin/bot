# Используем базовый образ с Java 17 (требуется для Spring Boot 3+)
FROM eclipse-temurin:17-jre-jammy

# Устанавливаем рабочую директорию внутри контейнера
WORKDIR /app

# Аргумент, чтобы Docker знал, где искать jar-файл
# (для Maven это target, для Gradle - build/libs)
ARG JAR_FILE=target/*.jar

# Копируем .jar файл из папки /target в контейнер и называем его app.jar
COPY ${JAR_FILE} app.jar

# Указываем порту 8075, что он будет "слушать"
EXPOSE 8075

# Команда, которая запускается при старте контейнера
ENTRYPOINT ["java", "-jar", "app.jar"]