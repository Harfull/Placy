# JarPlaceholders API v1

Base URL: `http://localhost:8080/api/v1`

---

## 1. Transform File

**Endpoint:**  
``POST /transform``

**Description:**  
Uploads a file (text, `.jar`, or `.zip`) and replaces placeholders in the file content. Returns the modified file.

**Request:**
- **Content-Type:** `multipart/form-data`
- **Form Fields:**

| Field          | Type   | Required | Description                                                                   |
|----------------|--------|----------|-------------------------------------------------------------------------------|
| `file`         | file   | Yes      | File to transform (text, `.jar`, `.zip`)                                      |
| `placeholders` | string | Yes      | JSON string mapping placeholders to values, e.g. `{"%%USERNAME%%":"Harfull"}` |

**Response:**
- **Content-Type:** `application/octet-stream`
- **Body:** Binary content of the transformed file
- **HTTP Status Codes:**
  - `200 OK` – File transformed successfully
  - `400 Bad Request` – Invalid file or placeholders JSON
  - `415 Unsupported Media Type` – Unsupported file type
  - `500 Internal Server Error` – Error during transformation

**Example cURL Request:**
```bash
curl -v -X POST http://localhost:8080/api/v1/transform \
  -F "file=@text.txt" \
  -F 'placeholders={"%%USERNAME%%":"Harfull"}' \
  --output text-modified.txt