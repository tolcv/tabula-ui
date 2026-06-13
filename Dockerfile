FROM eclipse-temurin:17-jdk AS build

RUN apt-get update -qq && apt-get install -y --no-install-recommends maven \
  && apt-get clean && rm -rf /var/lib/apt/lists/*

WORKDIR /build
COPY pom.xml .
# Download dependencies first (improves layer caching)
RUN mvn -q dependency:go-offline -DskipTests

COPY src ./src
RUN mvn -q -DskipTests package

# ---- runtime image ----
FROM eclipse-temurin:17-jre

# Install Tesseract for OCR support (optional - remove if not needed)
RUN apt-get update -qq && apt-get install -y --no-install-recommends \
    tesseract-ocr \
    tesseract-ocr-eng \
  && apt-get clean && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /build/target/tabula-ui-1.3.0.jar app.jar

EXPOSE 8080

ENV JAVA_OPTS="-Dfile.encoding=utf-8 -Xms256M -Xmx1024M"
ENV TABULA_DATA_DIR=""
ENV tabula_ocr_tessdata_path="/usr/share/tesseract-ocr/5/tessdata"
ENV tabula_ocr_native_library_path=""

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
