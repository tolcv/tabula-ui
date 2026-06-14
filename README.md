### UI for tabula-ui pdf extractor

based on https://github.com/tabulapdf/tabula

## Run options

App URL: http://localhost:8080

### 1) Run from source

Requirements:
- Java 11+
- Maven 3.8+

Commands:

```bash
mvn spring-boot:run
```

### 2) Run from JAR

Build and run:

```bash
mvn clean package -DskipTests
java -jar target/tabula-ui-1.3.0.jar
```

### 3) Run with Docker Compose

Build image and start container:

```bash
docker compose up --build
```

Run in background:

```bash
docker compose up -d --build
```

Stop containers:

```bash
docker compose down
```


