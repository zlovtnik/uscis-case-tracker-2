# USCIS Case Tracker gRPC Service

A pure functional Scala gRPC service for tracking USCIS immigration case statuses.

## Features

- ✅ **gRPC API**: Full-featured gRPC service with streaming support
- ✅ **Pure Functional**: Built with Cats Effect 3 for type-safe, composable effects
- ✅ **fs2-grpc**: Functional gRPC with fs2 streaming
- ✅ **Case Tracking**: Store and track multiple USCIS cases
- ✅ **Status History**: Track all status updates over time
- ✅ **Local Persistence**: JSON-based storage in `~/.uscis-tracker/`
- ✅ **Streaming**: Real-time case updates via server-streaming RPC
- ✅ **Health Checks**: Built-in health check endpoint
- ✅ **Reflection**: gRPC reflection enabled for grpcurl/grpcui

## Architecture

```
uscis-case-tracker/
├── src/main/
│   ├── protobuf/
│   │   └── service.proto             # gRPC service definition
│   ├── resources/
│   │   ├── application.conf          # Server configuration
│   │   └── logback.xml               # Logging configuration
│   └── scala/uscis/
│       ├── Main.scala                # IOApp gRPC server entry point
│       ├── api/
│       │   └── USCISClient.scala     # USCIS status checking
│       ├── effects/
│       │   ├── package.scala         # Core effect utilities
│       │   └── Logger.scala          # log4cats logging utilities
│       ├── grpc/
│       │   └── USCISCaseServiceImpl.scala  # gRPC service implementation
│       ├── models/
│       │   └── Case.scala            # Domain models
│       ├── persistence/
│       │   └── PersistenceManager.scala    # JSON storage layer
│       └── server/
│           └── GrpcServer.scala      # gRPC server lifecycle
├── build.sbt                         # SBT build with fs2-grpc plugin
└── project/
    └── plugins.sbt                   # sbt-fs2-grpc plugin
```

## Prerequisites

- **Java JDK 21+** - [Download](https://adoptium.net/)
- **SBT (Scala Build Tool)** - [Download](https://www.scala-sbt.org/download.html)
- **grpcurl** (optional) - For testing: `brew install grpcurl`

## Quick Start

### 1. Compile the Project

```bash
sbt compile
```

### 2. Start the gRPC Server

```bash
sbt run
```

The server will start on `0.0.0.0:50051`.

### 3. Test with grpcurl

```bash
# Health check
grpcurl -plaintext localhost:50051 uscis.proto.USCISCaseService/HealthCheck

# Add a case
grpcurl -plaintext -d '{"receipt_number": "IOE1234567890"}' \
  localhost:50051 uscis.proto.USCISCaseService/AddCase

# List all cases
grpcurl -plaintext -d '{}' localhost:50051 uscis.proto.USCISCaseService/ListCases

# Check case status
grpcurl -plaintext -d '{"receipt_number": "IOE1234567890"}' \
  localhost:50051 uscis.proto.USCISCaseService/CheckStatus

# Get a specific case
grpcurl -plaintext -d '{"receipt_number": "IOE1234567890", "include_history": true}' \
  localhost:50051 uscis.proto.USCISCaseService/GetCase

# Delete a case
grpcurl -plaintext -d '{"receipt_number": "IOE1234567890"}' \
  localhost:50051 uscis.proto.USCISCaseService/DeleteCase

# Export cases
grpcurl -plaintext -d '{}' localhost:50051 uscis.proto.USCISCaseService/ExportCases

# List available services
grpcurl -plaintext localhost:50051 list

# Describe service
grpcurl -plaintext localhost:50051 describe uscis.proto.USCISCaseService
```

## gRPC Service Definition

```protobuf
service USCISCaseService {
  // Add or update a case
  rpc AddCase(AddCaseRequest) returns (AddCaseResponse);
  
  // Get a specific case by receipt number
  rpc GetCase(GetCaseRequest) returns (GetCaseResponse);
  
  // List all cases with optional filtering
  rpc ListCases(ListCasesRequest) returns (ListCasesResponse);
  
  // Check status of a case (fetches from USCIS)
  rpc CheckStatus(CheckStatusRequest) returns (CheckStatusResponse);
  
  // Delete a case
  rpc DeleteCase(DeleteCaseRequest) returns (DeleteCaseResponse);
  
  // Export all cases
  rpc ExportCases(ExportCasesRequest) returns (ExportCasesResponse);
  
  // Import cases
  rpc ImportCases(ImportCasesRequest) returns (ImportCasesResponse);
  
  // Stream case status updates (server streaming)
  rpc WatchCases(WatchCasesRequest) returns (stream CaseUpdate);
  
  // Health check
  rpc HealthCheck(HealthCheckRequest) returns (HealthCheckResponse);
}
```

## Configuration

Configuration is managed in `src/main/resources/application.conf`:

```hocon
grpc {
  host = "0.0.0.0"
  port = 50051
}

uscis {
  api {
    url = "https://egov.uscis.gov/casestatus/mycasestatus.do"
    token = ""
    timeout = 30
  }
  check {
    max-retries = 3
    batch-delay = 1000
    max-concurrency = 5
  }
}
```

## Data Storage

- **Location**: `~/.uscis-tracker/cases.json`
- **Format**: JSON with full case history
- **Structure**:
  ```json
  [
    {
      "receiptNumber": "IOE1234567890",
      "statusUpdates": [
        {
          "receiptNumber": "IOE1234567890",
          "caseType": "I-485",
          "currentStatus": "Case Was Received",
          "lastUpdated": "2026-02-03T10:30:00",
          "details": "Your case was received..."
        }
      ]
    }
  ]
  ```

## Receipt Number Format

USCIS receipt numbers consist of **3 letters + 10 digits**:

| Prefix | Service Center |
|--------|----------------|
| IOE    | Online Filing |
| WAC    | California Service Center |
| LIN    | Nebraska Service Center |
| EAC    | Vermont Service Center |
| SRC    | Texas Service Center |
| NBC    | National Benefits Center |
| MSC    | Missouri Service Center |

Example: `IOE1234567890`, `WAC9012345678`

## Module Descriptions

### Main (`Main.scala`)
- `IOApp` entry point with graceful shutdown
- Uses `IO.never` for long-running server

### gRPC Service (`USCISCaseServiceImpl.scala`)
- Implements `USCISCaseServiceFs2Grpc[IO, Metadata]`
- Pure functional implementation
- Type-safe proto conversion

### Server (`GrpcServer.scala`)
- Resource-based lifecycle management
- Automatic shutdown on termination
- gRPC reflection enabled

### Persistence (`PersistenceManager.scala`)
- File-based JSON storage with log4cats logging
- CRUD operations wrapped in `IO`
- Import/export functionality

### API Client (`USCISClient.scala`)
- Status checking with retry logic
- Receipt number validation
- Batch checking with concurrency control via `Semaphore`

## Technology Stack

- **Scala 2.13** - Functional programming language
- **Cats Effect 3** - Pure functional effects
- **fs2-grpc** - Functional gRPC for Scala
- **log4cats** - Pure functional logging
- **Play JSON** - JSON serialization
- **Logback** - Logging backend

## Docker (Optional)

```dockerfile
FROM eclipse-temurin:21-jdk

WORKDIR /app
COPY . .

RUN sbt compile

EXPOSE 50051
CMD ["sbt", "run"]
```

Build and run:
```bash
docker build -t uscis-tracker .
docker run -p 50051:50051 uscis-tracker
```

## Troubleshooting

### Compilation Errors

```bash
# Clean and rebuild
sbt clean compile
```

### Port Already in Use

Change the port in `application.conf`:
```hocon
grpc.port = 50052
```

### Metals IDE Issues

```bash
sbt bloopInstall
# Then in VS Code: Command Palette > Metals: Import build
```

## Legal Disclaimer

This tool is for **personal use only** and is not affiliated with or endorsed by USCIS. 

- Do not use for commercial purposes
- Respect USCIS website terms of service
- Do not overload USCIS servers with automated requests
- Official status should be verified at: https://egov.uscis.gov/casestatus

## License

MIT License - Feel free to modify and use for personal projects.

---

**Version**: 0.1.0  
**Last Updated**: February 2026
