# SpendWise Backend – Spring Boot REST API & LLM Gateway

This is the backend service for **SpendWise – AI-Powered Smart Expense Tracker**.

## Technology Stack
- **Framework:** Java 21, Spring Boot 3.3.4
- **Modules:** Spring Web, Spring Validation, Jackson
- **AI / LLM Integration:** Spring AI / Prompt Templates with OpenAI & Gemini compatibility
- **Port:** `http://localhost:8080`

## REST API Endpoints

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/v1/transactions/analyze` | Ingests raw banking text and normalizes via LLM prompt template |
| `GET` | `/api/v1/transactions` | Lists all normalized transactions |
| `POST` | `/api/v1/transactions` | Saves a new transaction record |
| `DELETE` | `/api/v1/transactions/{id}` | Deletes a transaction by ID |
| `GET` | `/api/v1/health` | Health-check endpoint for frontend ping |

## Running the Backend

Ensure Java 21 is installed:
```bash
# Run with Maven Wrapper or local Maven
mvn spring-boot:run
```

Once running, the React frontend running on `http://localhost:5173` can connect directly by opening **Settings** in the top navbar and switching the mode to **Live Java Spring Boot REST API**.
