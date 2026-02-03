# Project Structure

```
uscis-case-tracker/
├── build.sbt                              # SBT build with fs2-grpc plugin
├── Makefile                               # Build & gRPC client commands
├── QUICKSTART.md                          # 5-minute setup guide
├── README.md                              # Full documentation
│
├── project/
│   ├── build.properties                   # SBT version
│   └── plugins.sbt                        # sbt-fs2-grpc plugin
│
├── src/main/
│   ├── protobuf/
│   │   └── service.proto                  # gRPC service definition
│   │
│   ├── resources/
│   │   ├── application.conf               # Server & API configuration
│   │   └── logback.xml                    # Logging configuration
│   │
│   └── scala/uscis/
│       ├── Main.scala                     # IOApp entry point
│       │
│       ├── api/
│       │   └── USCISClient.scala          # USCIS status checking client
│       │
│       ├── effects/
│       │   ├── package.scala              # Core effect utilities
│       │   └── Logger.scala               # log4cats utilities
│       │
│       ├── grpc/
│       │   └── USCISCaseServiceImpl.scala # gRPC service implementation
│       │
│       ├── models/
│       │   └── Case.scala                 # Domain models (CaseStatus, CaseHistory)
│       │
│       ├── persistence/
│       │   └── PersistenceManager.scala   # JSON file storage
│       │
│       └── server/
│           └── GrpcServer.scala           # gRPC server lifecycle
│
└── target/
    └── scala-2.13/
        └── src_managed/
            ├── fs2-grpc/                  # Generated fs2-grpc service traits
            └── scalapb/                   # Generated protobuf messages
```

## Key Components

### Entry Point
- **Main.scala**: `IOApp` that starts the gRPC server and blocks until SIGINT

### gRPC Layer
- **service.proto**: Service definition with 9 RPC methods (AddCase, GetCase, ListCases, CheckStatus, DeleteCase, ExportCases, ImportCases, WatchCases, HealthCheck)
- **USCISCaseServiceImpl.scala**: Pure functional implementation using `IO`
- **GrpcServer.scala**: Resource-based server lifecycle with graceful shutdown

### Domain
- **Case.scala**: `CaseStatus` and `CaseHistory` models with Play JSON codecs
- **USCISClient.scala**: USCIS API client with retry logic and rate limiting

### Infrastructure
- **PersistenceManager.scala**: File-based JSON storage in `~/.uscis-tracker/`
- **effects/**: Logging and utility type classes

## Generated Code

After `sbt compile`, protobuf generates:
- `uscis.proto.service._` - All message types (AddCaseRequest, CaseStatus, etc.)
- `uscis.proto.service.USCISCaseServiceFs2Grpc` - fs2-grpc service trait

## Configuration

| Setting | Default | Description |
|---------|---------|-------------|
| `grpc.host` | 0.0.0.0 | Server bind address |
| `grpc.port` | 50051 | Server port |
| `uscis.check.max-retries` | 3 | Retry attempts for status checks |
| `uscis.check.max-concurrency` | 5 | Max parallel status checks |
