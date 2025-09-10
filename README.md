# Placy

![Java](https://img.shields.io/badge/Java-17+-brightgreen.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.0-brightgreen.svg)
![License](https://img.shields.io/badge/License-MIT-blue.svg)

**Placy** is a high-performance, enterprise-grade file transformation service that processes placeholders in files and archives with lightning speed. Designed to handle massive files (100MB+) efficiently while supporting an extensive range of file formats including archives, documents, images, and text files.

## 🚀 Features

### **Comprehensive File Format Support**
- **📦 Archives**: JAR, WAR, EAR, ZIP, TAR, 7Z, GZIP, BZIP2, XZ and many more
- **📄 Documents**: Word (.docx, .doc), Excel (.xlsx, .xls), PowerPoint (.pptx), PDF
- **🖼️ Images**: JPEG, PNG, TIFF, GIF with metadata editing support
- **📝 Text Files**: All programming languages, configuration files, markup languages
- **☕ Java Bytecode**: Class file transformation using ASM

### **High-Performance Architecture**
- **⚡ Instant Response**: Advanced caching for near-instant processing of repeated operations
- **🗂️ Large File Optimization**: Streaming architecture handles 100MB+ files without memory issues
- **🔄 Parallel Processing**: Automatic parallel processing for files >10MB
- **🧠 Memory Efficient**: Processes files in chunks to minimize memory footprint
- **🔧 Smart Buffering**: Dynamic buffer sizing based on file size
- **⚡ Asynchronous Processing**: Optional concurrent file processing for maximum throughput

### **Enterprise Security**
- **🔐 API Key Authentication**: Optional SECRET_KEY validation
- **🛡️ HTTPS Support**: TLS 1.3/1.2 with modern cipher suites
- **🍪 Secure Sessions**: HTTP-only, secure, same-site cookies
- **📊 Health Monitoring**: Built-in actuator endpoints

## 📋 Supported File Types

### Archives
```
JAR, WAR, EAR, AAR, ZIP, APK, XPI, CRUX, VSIX, NUPKG
TAR, TAR.GZ, TAR.BZ2, TAR.XZ, 7Z, GZIP, BZIP2, XZ
```

### Documents
```
Word: .docx, .doc
Excel: .xlsx, .xls  
PowerPoint: .pptx
PDF: .pdf (content-only)
Text: .txt, .md, .json, .xml, .html, .css, .js, .properties, .yml, .yaml
```

### Images (with Metadata Editing)
```
JPEG, PNG, TIFF, GIF, BMP, ICO, SVG, WEBP, RAW formats
HEIC, HEIF, AVIF, and 50+ other formats
```

### Programming Languages
```
Java, JavaScript, TypeScript, Python, C/C++, C#, Go, Rust, Kotlin, Scala
HTML, CSS, XML, JSON, YAML, SQL, Shell scripts, and more
```

## 🛠️ Installation

### Prerequisites
- Java 17 or higher
- 2GB RAM minimum (4GB recommended for large files)

### Quick Start

1. **Download the latest release**
   ```bash
   wget https://github.com/Harfull/placy/releases/latest/download/Placy.jar
   ```

2. **Run the application**
   ```bash
   java -jar Placy.jar
   ```

3. **Access the API**
   ```
   http://localhost:8080
   ```

### Environment Configuration

```bash
# Server Configuration
export SERVER_PORT=8080
export HTTPS_ENABLED=false

# Security (Optional)
export SECRET_KEY=your-secret-key-here

# SSL Configuration (if HTTPS enabled)
export SSL_KEYSTORE_PATH=/path/to/keystore.p12
export SSL_KEYSTORE_PASSWORD=changeit
export SSL_KEY_ALIAS=placy
```

### API Endpoint

**POST** `/api/v1/transform`

#### Request
```bash
curl -X POST http://localhost:8080/api/v1/transform \
  -H "Content-Type: multipart/form-data" \
  -H "X-Secret-Key: your-key" \
  -F "file=@your-file.jar" \
  -F 'placeholders={"${VERSION}":"1.2.3","${ENV}":"production"}'
```

#### Response
- **Success**: Returns the transformed file as binary data
- **Error**: JSON error response with details

#### Headers
- `X-Secret-Key`: Required if SECRET_KEY environment variable is set
- `Content-Type`: `multipart/form-data`

## 📊 Performance Benchmarks

| File Size | File Type | Processing Time | Memory Usage |
|-----------|-----------|----------------|--------------|
| 10MB      | JAR       | ~500ms        | 50MB         |
| 50MB      | ZIP       | ~2s           | 100MB        |
| 100MB     | TAR.GZ    | ~4s           | 150MB        |
| 500MB     | Archive   | ~15s          | 200MB        |

*Benchmarks on Intel i7, 16GB RAM*

## 🏗️ Architecture

### Modular Design
```
📦 Placy
├── 🎮 Controllers (REST API)
├── 🔍 FileTypeDetector (Smart file type detection)
├── 🌊 StreamProcessor (High-performance streaming)
├── 📦 ArchiveProcessor (All archive formats)
├── 🖼️ ImageProcessor (Metadata editing)
├── 📄 DocumentProcessor (Office documents)
└── ☕ ClassProcessor (Java bytecode)
```

### Processing Pipeline
1. **File Type Detection**: Intelligent detection using extensions and content analysis
2. **Route to Processor**: Files are routed to specialized processors
3. **Transformation**: Placeholders replaced using optimized algorithms
4. **Caching**: Results cached for instant repeat operations
5. **Response**: Processed file returned to client

## 🔒 Security Features

- **API Key Protection**: Optional SECRET_KEY validation for API access
- **HTTPS/TLS Support**: Full SSL/TLS support with modern security
- **Input Validation**: Comprehensive validation of all inputs
- **Memory Protection**: Streaming prevents memory exhaustion attacks
- **Error Handling**: Secure error responses without information leakage

## 🚀 Advanced Features

### Nested Archive Support
Placy can process nested archives (e.g., JAR inside ZIP inside TAR.GZ) recursively.

### Smart Caching
- **File Type Cache**: Instant file type detection for known files
- **Processing Cache**: Cached results for identical file/placeholder combinations
- **CRC32 Cache**: Optimized archive integrity checking

### Parallel Processing
Large files are automatically processed in parallel chunks for maximum performance.

### Memory Management
- **Streaming I/O**: Files processed without loading entirely into memory
- **Dynamic Buffers**: Buffer sizes adapt to file size for optimal performance
- **Garbage Collection**: Efficient memory cleanup prevents memory leaks

## 🛠️ Development

### Building from Source
```bash
git clone https://github.com/yourusername/placy.git
cd placy
### Asynchronous Processing
Placy supports high-performance asynchronous file processing for optimal throughput:

#### **Async Processing Mode** (`ASYNC_PROCESSING=true`)
- **Concurrent Processing**: Multiple files processed simultaneously using thread pools
- **Non-blocking Operations**: API calls return immediately with processing handled asynchronously
- **Intelligent Thread Management**: Dynamic thread pool sizing based on system capabilities
- **Graceful Degradation**: Falls back to synchronous processing if thread pool is saturated
- **Error Isolation**: Individual file errors don't affect other files in batch operations

#### **Synchronous Processing Mode** (Default)
- **Sequential Processing**: Files processed one at a time in order
- **Predictable Resource Usage**: Lower memory footprint and CPU usage
- **Simplified Error Handling**: Immediate error reporting without async complexity

#### **Performance Characteristics**
| Processing Mode | Single File | Multiple Files | Resource Usage | Throughput |
|----------------|-------------|----------------|----------------|------------|
| Synchronous    | Standard    | Sequential     | Low            | Moderate   |
| Asynchronous   | Standard    | Concurrent     | Higher         | High       |

#### **Thread Pool Configuration**
- **Core Pool Size**: `max(2, availableProcessors / 2)`
- **Max Pool Size**: `max(4, availableProcessors * 2)`
- **Queue Capacity**: 100 tasks
- **Keep Alive Time**: 60 seconds
- **Rejection Policy**: Graceful fallback to synchronous execution

./gradlew build
```

### Running in Development
```bash
./gradlew bootRun
```

### Testing
```bash
./gradlew test
```

## 📝 Configuration

### Application Properties
```properties
# Server Configuration
server.port=8080
server.servlet.context-path=/

# File Upload Limits
spring.servlet.multipart.max-file-size=50MB
spring.servlet.multipart.max-request-size=50MB

# SSL Configuration
server.ssl.enabled=false
server.ssl.key-store=classpath:keystore.p12
server.ssl.key-store-password=changeit
```

### Supported Extensions
Add new file extensions by editing files in `src/main/resources/supported/`:
- `text.txt` - Text file extensions
- `archives.txt` - Archive file extensions  
- `images.txt` - Image file extensions
- `documents.txt` - Document file extensions

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- **Apache POI** - Office document processing
- **Apache Commons Compress** - Archive format support
- **ASM** - Java bytecode manipulation
- **Spring Boot** - Application framework
- **TwelveMonkeys ImageIO** - Enhanced image format support

---

**Built with ❤️ for developers who need fast, reliable file processing**
