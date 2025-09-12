# Placy

![Java](https://img.shields.io/badge/Java-17+-brightgreen.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.0-brightgreen.svg)
![License](https://img.shields.io/badge/License-MIT-blue.svg)

A high-performance file transformation service that replaces placeholders in files and archives. Built for speed and efficiency with support for large files (100MB+) and extensive file format compatibility.

## Features

- **Wide Format Support**: Archives (JAR, ZIP, TAR), documents (Word, Excel, PDF), images, text files, and Java bytecode
- **High Performance**: Streaming architecture with parallel processing for large files
- **Smart Caching**: Near-instant processing of repeated operations
- **Memory Efficient**: Processes large files without loading entirely into memory
- **Enterprise Security**: API key authentication and HTTPS support

## Supported File Types

**Archives**: JAR, WAR, EAR, ZIP, TAR, 7Z, GZIP and more  
**Documents**: Word (.docx), Excel (.xlsx), PowerPoint (.pptx), PDF  
**Images**: JPEG, PNG, TIFF, GIF with metadata editing  
**Text Files**: All programming languages, config files, markup  
**Java**: Class file transformation using ASM

## Quick Start

1. **Download and run**
   ```bash
   wget https://github.com/Harfull/placy/releases/latest/download/Placy.jar
   java -jar Placy.jar
   ```

2. **Transform a file**
   ```bash
   curl -X POST http://localhost:8080/api/v1/transform \
     -H "Content-Type: multipart/form-data" \
     -F "file=@your-file.jar" \
     -F 'placeholders={"${VERSION}":"1.2.3","${ENV}":"production"}'
   ```

## Configuration

Set optional environment variables:

```bash
export SERVER_PORT=8080          # Default port
export SECRET_KEY=your-key       # Optional API key protection  
export HTTPS_ENABLED=false       # Enable HTTPS
export ASYNC_PROCESSING=false    # Enable async processing
```

## API Reference

**Endpoint**: `POST /api/v1/transform`

**Headers**:
- `Content-Type: multipart/form-data`
- `X-Secret-Key: your-key` (if SECRET_KEY is set)

**Parameters**:
- `file`: The file to transform
- `placeholders`: JSON object with placeholder mappings

**Response**: Transformed file as binary data or JSON error

## Performance Features

- **Streaming I/O**: Handles large files without memory issues
- **Parallel Processing**: Automatic for files >10MB
- **Smart Caching**: File type detection and processing results
- **Asynchronous Mode**: Optional concurrent processing for maximum throughput

## Development

```bash
git clone https://github.com/Harfull/placy.git
cd placy
./gradlew build
./gradlew bootRun    # Development server
./gradlew test       # Run tests
```

## License

MIT License - see [LICENSE](LICENSE) file for details.
