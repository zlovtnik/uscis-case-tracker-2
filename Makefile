# USCIS Case Tracker gRPC Server - Makefile
# Scala/SBT project with fs2-grpc

.PHONY: all compile build run test clean clean-all bloop package console deps get delete export services describe check-sbt check-grpcurl help proto health add list check-case

# Default target
all: compile

# Check if sbt is installed
check-sbt:
	@command -v sbt >/dev/null 2>&1 || { echo "Error: sbt is not installed. Install from https://www.scala-sbt.org/download.html"; exit 1; }

# Compile the project (includes proto generation)
compile: check-sbt
	@echo "Compiling USCIS Case Tracker gRPC Server..."
	sbt compile
	@echo "✓ Compilation successful"

# Alias for compile
build: compile

# Start the gRPC server
run: check-sbt
	@echo "Starting gRPC server on port 50051..."
	sbt run

# Generate proto files only
proto: check-sbt
	sbt compile

# Run tests
test: check-sbt
	sbt test

# Clean build artifacts
clean: check-sbt
	sbt clean
	@echo "✓ Build artifacts cleaned"

# Full clean including bloop and metals
clean-all: clean
	rm -rf .bloop .metals project/target target

# Regenerate Bloop files for IDE
bloop: check-sbt
	sbt bloopInstall

# Package as JAR
package: check-sbt
	sbt package

# Interactive SBT console
console: check-sbt
	sbt console

# Show project dependencies
deps: check-sbt
	sbt dependencyTree

# ============================================================
# gRPC Client Commands (requires grpcurl)
# ============================================================

check-grpcurl:
	@command -v grpcurl >/dev/null 2>&1 || { echo "Error: grpcurl is not installed. Install with: brew install grpcurl"; exit 1; }

# Health check
health: check-grpcurl
	grpcurl -plaintext localhost:50051 uscis.proto.USCISCaseService/HealthCheck

# Add a case (use: make add RECEIPT=IOE1234567890)
add: check-grpcurl
	@if [ -z "$(RECEIPT)" ]; then \
		echo "Usage: make add RECEIPT=IOE1234567890"; \
		exit 1; \
	fi
	jq -n --arg receipt "$(RECEIPT)" '{receipt_number:$$receipt}' | \
		grpcurl -plaintext -d @ localhost:50051 uscis.proto.USCISCaseService/AddCase

# List all cases
list: check-grpcurl
	grpcurl -plaintext -d '{}' localhost:50051 uscis.proto.USCISCaseService/ListCases

# Check case status (use: make check-case RECEIPT=IOE1234567890)
check-case: check-grpcurl
	@if [ -z "$(RECEIPT)" ]; then \
		echo "Usage: make check-case RECEIPT=IOE1234567890"; \
		exit 1; \
	fi
	jq -n --arg receipt "$(RECEIPT)" '{receipt_number:$$receipt, save_to_database:true}' | \
		grpcurl -plaintext -d @ localhost:50051 uscis.proto.USCISCaseService/CheckStatus

# Get case with history (use: make get RECEIPT=IOE1234567890)
get: check-grpcurl
	@if [ -z "$(RECEIPT)" ]; then \
		echo "Usage: make get RECEIPT=IOE1234567890"; \
		exit 1; \
	fi
	jq -n --arg receipt "$(RECEIPT)" '{receipt_number:$$receipt, include_history:true}' | \
		grpcurl -plaintext -d @ localhost:50051 uscis.proto.USCISCaseService/GetCase

# Delete a case (use: make delete RECEIPT=IOE1234567890)
delete: check-grpcurl
	@if [ -z "$(RECEIPT)" ]; then \
		echo "Usage: make delete RECEIPT=IOE1234567890"; \
		exit 1; \
	fi
	jq -n --arg receipt "$(RECEIPT)" '{receipt_number:$$receipt}' | \
		grpcurl -plaintext -d @ localhost:50051 uscis.proto.USCISCaseService/DeleteCase

# Export all cases
export: check-grpcurl
	grpcurl -plaintext -d '{}' localhost:50051 uscis.proto.USCISCaseService/ExportCases

# List available gRPC services
services: check-grpcurl
	grpcurl -plaintext localhost:50051 list

# Describe the service
describe: check-grpcurl
	grpcurl -plaintext localhost:50051 describe uscis.proto.USCISCaseService

# ============================================================
# Help
# ============================================================

help:
	@echo "USCIS Case Tracker gRPC Server"
	@echo ""
	@echo "Build Commands:"
	@echo "  make compile    - Compile the project (includes proto generation)"
	@echo "  make run        - Start the gRPC server on port 50051"
	@echo "  make test       - Run tests"
	@echo "  make clean      - Clean build artifacts"
	@echo "  make clean-all  - Full clean including IDE files"
	@echo "  make bloop      - Regenerate Bloop files for IDE"
	@echo ""
	@echo "gRPC Client Commands (requires grpcurl):"
	@echo "  make health               - Health check"
	@echo "  make add RECEIPT=XXX      - Add a case"
	@echo "  make list                 - List all cases"
	@echo "  make get RECEIPT=XXX      - Get case with history"
	@echo "  make check-case RECEIPT=XXX - Check case status"
	@echo "  make delete RECEIPT=XXX   - Delete a case"
	@echo "  make export               - Export all cases"
	@echo "  make services             - List gRPC services"
	@echo "  make describe             - Describe the service"

