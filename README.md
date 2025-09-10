# JarPlaceholders

A Spring Boot service for transforming files by replacing placeholders with specified values. Supports JAR files, ZIP archives, and various text-based file formats.

## Features

### Supported File Types

#### Archives
- **JAR files** (.jar) - Full support including Java bytecode transformation
- **ZIP files** (.zip) - Standard ZIP archive processing

#### Text Files
- Configuration files: `.properties`, `.conf`, `.ini`, `.yaml`, `.yml`
- Data formats: `.json`, `.xml`, `.csv`, `.txt`
- Documentation: `.md`, `.rst`
- Scripts: `.sh`, `.bat`, `.ps1`, `.sql`
- Source code: `.java`, `.js`, `.ts`, `.py`, `.cpp`, `.c`, `.h`, `.cs`, `.php`, `.rb`, `.go`, `.rs`, `.kt`, `.swift`, `.m`, `.pl`, `.r`
- Web files: `.html`, `.htm`, `.css`, `.scss`, `.less`
- Logs: `.log`

### Key Capabilities

- **Placeholder Replacement**: Replace text placeholders in files with custom values
- **Archive Processing**: Transform files within JAR and ZIP archives
- **Java Bytecode Transformation**: Modify string literals in compiled Java classes using ASM
- **Smart File Detection**: Automatically detects text vs binary files
- **Security**: Built-in authentication and HTTPS support
- **REST API**: Simple HTTP interface for file transformation

## Quick Start

### Prerequisites

- Java 17 or later
- Gradle 7.0+ (or use included wrapper)

### Running the Application

1. **Using Gradle Wrapper (Recommended)**:
   ```bash
   ./gradlew bootRun
   ```

2. **Using Pre-built JAR**:
   ```bash
   java -jar build/libs/server.jar
   ```

3. **Building from Source**:
   ```bash
   ./gradlew build
   java -jar build/libs/server.jar
   ```

The service will start on port 8080 by default.

### Basic Usage

Transform a file by sending a POST request to `/api/v1/transform`:

```bash
curl -X POST http://localhost:8080/api/v1/transform \
  -F "file=@example.jar" \
  -F 'placeholders={"${VERSION}":"1.2.3","${API_URL}":"https://api.example.com"}'
```

## Configuration

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8080` | HTTP server port |
| `HTTPS_ENABLED` | `false` | Enable HTTPS |
| `SSL_KEYSTORE_PATH` | `classpath:keystore.p12` | SSL keystore location |
| `SSL_KEYSTORE_PASSWORD` | `changeit` | SSL keystore password |
| `SSL_KEYSTORE_TYPE` | `PKCS12` | SSL keystore type |
| `SSL_KEY_ALIAS` | `jarplaceholders` | SSL key alias |
| `SSL_KEY_PASSWORD` | `changeit` | SSL key password |

### HTTPS Configuration

To enable HTTPS, set `HTTPS_ENABLED=true` and provide a valid SSL certificate:

```bash
export HTTPS_ENABLED=true
export SSL_KEYSTORE_PATH=/path/to/keystore.p12
export SSL_KEYSTORE_PASSWORD=your_password
```

## Examples

### Transform a JAR file

```bash
# Replace version placeholders in a JAR
curl -X POST http://localhost:8080/api/v1/transform \
  -F "file=@app.jar" \
  -F 'placeholders={"${app.version}":"2.1.0","${build.number}":"123"}' \
  -o transformed-app.jar
```

### Transform a configuration file

```bash
# Replace environment-specific values in a config file
curl -X POST http://localhost:8080/api/v1/transform \
  -F "file=@config.properties" \
  -F 'placeholders={"${DB_HOST}":"prod-db.example.com","${API_KEY}":"abc123"}' \
  -o config-prod.properties
```

### Transform a ZIP archive

```bash
# Process all text files within a ZIP
curl -X POST http://localhost:8080/api/v1/transform \
  -F "file=@deployment.zip" \
  -F 'placeholders={"${ENV}":"production","${REGION}":"us-west-2"}' \
  -o deployment-prod.zip
```

## How It Works

### File Processing Pipeline

1. **File Type Detection**: Determines if the file is an archive, text file, or binary
2. **Archive Extraction**: For JAR/ZIP files, extracts all entries
3. **Content Analysis**: Identifies which files can be transformed (text-based)
4. **Transformation**: 
   - Text files: Direct string replacement
   - Java classes: ASM-based bytecode transformation
   - Binary files: Preserved unchanged
5. **Archive Reconstruction**: Rebuilds archives with transformed content
6. **Metadata Preservation**: Maintains file attributes, timestamps, and structure

### Java Bytecode Transformation

For `.class` files within JARs, the service uses the ASM library to:
- Parse Java bytecode
- Locate string literals (LDC instructions)
- Replace matching placeholders
- Regenerate valid bytecode

This enables transformation of compiled Java applications without source code.

## Architecture

- **Spring Boot**: Web framework and dependency injection
- **ASM**: Java bytecode manipulation
- **Gson**: JSON parsing for placeholders
- **Spring Security**: Authentication and authorization
- **SLF4J**: Logging framework

## Building and Deployment

### Development Build

```bash
./gradlew build
```

### Production Build

```bash
./gradlew bootJar
```

The resulting `server.jar` contains all dependencies and can be deployed standalone.

### Docker Deployment

Create a `Dockerfile`:

```dockerfile
FROM openjdk:17-jre-slim

COPY build/libs/server.jar /app/server.jar
EXPOSE 8080

CMD ["java", "-jar", "/app/server.jar"]
```

Build and run:

```bash
docker build -t jarplaceholders .
docker run -p 8080:8080 jarplaceholders
```

## Security Considerations

- The service includes Spring Security for authentication
- HTTPS support with configurable SSL certificates
- Input validation for file types and JSON payloads
- Resource management with automatic cleanup

## Troubleshooting

### Common Issues

1. **Unsupported file type**: Check if your file extension is in the supported list
2. **Invalid JSON**: Ensure placeholders parameter is valid JSON
3. **Large files**: The service loads files into memory; ensure adequate heap space
4. **SSL errors**: Verify certificate configuration and file permissions

### Logging

The service uses SLF4J for logging. Key log messages include:
- File transformation start/completion
- Placeholder replacement counts
- Error details for failed transformations

## License

This project is open source. See the license file for details.

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests if applicable
5. Submit a pull request

## Support

For questions or issues, please open a GitHub issue or contact the development team.
