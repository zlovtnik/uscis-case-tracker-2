# Quick Start Guide

## 5-Minute Setup

### 1. Install Prerequisites

**macOS** (using Homebrew):
```bash
brew install openjdk@17 sbt grpcurl
```

**Linux** (Ubuntu/Debian):
```bash
sudo apt update
sudo apt install openjdk-17-jdk
# Install sbt from https://www.scala-sbt.org/download.html
# Install grpcurl from https://github.com/fullstorydev/grpcurl
```

### 2. Compile the Project

```bash
cd uscis-case-tracker
sbt compile
```

### 3. Start the gRPC Server

```bash
sbt run
```

The server starts on port `50051`.

### 4. Test with grpcurl

Open a new terminal:

```bash
# Health check
grpcurl -plaintext localhost:50051 uscis.proto.USCISCaseService/HealthCheck

# Add a case
grpcurl -plaintext -d '{"receipt_number": "IOE1234567890"}' \
  localhost:50051 uscis.proto.USCISCaseService/AddCase

# List cases
grpcurl -plaintext -d '{}' \
  localhost:50051 uscis.proto.USCISCaseService/ListCases
```

That's it! ✓

## Common Commands

```bash
# Health check
grpcurl -plaintext localhost:50051 uscis.proto.USCISCaseService/HealthCheck

# Add case
grpcurl -plaintext -d '{"receipt_number": "IOE1234567890"}' \
  localhost:50051 uscis.proto.USCISCaseService/AddCase

# Get case with history
grpcurl -plaintext -d '{"receipt_number": "IOE1234567890", "include_history": true}' \
  localhost:50051 uscis.proto.USCISCaseService/GetCase

# Check status (fetches from USCIS)
grpcurl -plaintext -d '{"receipt_number": "IOE1234567890", "save_to_database": true}' \
  localhost:50051 uscis.proto.USCISCaseService/CheckStatus

# List all cases
grpcurl -plaintext -d '{}' localhost:50051 uscis.proto.USCISCaseService/ListCases

# Delete case
grpcurl -plaintext -d '{"receipt_number": "IOE1234567890"}' \
  localhost:50051 uscis.proto.USCISCaseService/DeleteCase

# Export cases
grpcurl -plaintext -d '{}' localhost:50051 uscis.proto.USCISCaseService/ExportCases
```

## Explore the API

```bash
# List all available services
grpcurl -plaintext localhost:50051 list

# Describe service
grpcurl -plaintext localhost:50051 describe uscis.proto.USCISCaseService

# Describe a specific message type
grpcurl -plaintext localhost:50051 describe uscis.proto.AddCaseRequest
```

## Need Help?

- Check `README.md` for full documentation
- View proto definitions in `src/main/protobuf/service.proto`
- Configuration in `src/main/resources/application.conf`
