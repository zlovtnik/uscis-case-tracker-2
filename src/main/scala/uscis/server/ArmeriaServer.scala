package uscis.server

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._
import com.linecorp.armeria.common.{HttpRequest, HttpResponse, HttpStatus, MediaType}
import com.linecorp.armeria.server.{Server, ServiceRequestContext, HttpService}
import com.linecorp.armeria.server.grpc.GrpcService
import com.linecorp.armeria.server.healthcheck.HealthCheckService
import io.grpc.protobuf.services.ProtoReflectionService
import org.http4s.client.Client
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import uscis.grpc.USCISCaseServiceGrpcImpl
import uscis.proto.service.USCISCaseServiceGrpc
import uscis.persistence.PersistenceManager
import uscis.api.{USCISApiClient, USCISConfig}
import com.typesafe.config.ConfigFactory
import java.time.Instant
import java.util.concurrent.{ExecutorService, Executors}
import scala.concurrent.ExecutionContext

/**
 * Unified Armeria server supporting:
 * - gRPC (HTTP/2)
 * - gRPC-Web (HTTP/1.1 with base64/binary encoding)
 * - HTTP health checks (HTTP/1.1)
 * 
 * All protocols served on a single port, compatible with cloud platforms
 * like Render, Railway, and Fly.io that only expose one port.
 */
object ArmeriaServer {

  case class ServerConfig(host: String, port: Int)

  private val logger: Logger[IO] = Slf4jLogger.getLoggerFromClass[IO](this.getClass)

  def loadConfig: IO[ServerConfig] = IO.delay {
    val config = ConfigFactory.load()
    // Use PORT env var (set by Render/Railway) or fall back to configured port
    // Safe parsing with toIntOption to avoid NumberFormatException
    val port = sys.env.get("PORT")
      .flatMap(_.toIntOption)
      .getOrElse(config.getInt("grpc.port"))
    ServerConfig(
      host = config.getString("grpc.host"),
      port = port
    )
  }

  /**
   * Create a bounded ExecutionContext for gRPC service handlers.
   * Returns a Resource that properly shuts down the executor on release.
   */
  private def boundedExecutionContext: Resource[IO, ExecutionContext] = {
    val numThreads = math.max(4, Runtime.getRuntime.availableProcessors())
    Resource.make(
      IO.delay {
        val executor: ExecutorService = Executors.newFixedThreadPool(numThreads)
        ExecutionContext.fromExecutorService(executor)
      }
    )(ec => IO.blocking {
      ec match {
        case eces: ExecutionContext with AutoCloseable => eces.close()
        case _ => () // No-op for non-closeable execution contexts
      }
    }.handleError(_ => ()))
  }

  /**
   * Create a managed Armeria server resource.
   * 
   * Supports:
   * - gRPC over HTTP/2
   * - gRPC-Web over HTTP/1.1 (for browser clients)
   * - HTTP health endpoints at /health, /ready, /live
   */
  def resource(
    config: ServerConfig,
    persistence: PersistenceManager,
    httpClient: Client[IO],
    tokenCache: Ref[IO, Option[USCISApiClient.CachedToken]]
  ): Resource[IO, Server] = {
    for {
      ec        <- boundedExecutionContext
      logger    <- Resource.eval(Slf4jLogger.create[IO])
      startTime <- Resource.eval(IO.realTimeInstant)
      _         <- Resource.eval(
                     if (USCISConfig.hasCredentials)
                       logger.info("USCIS API credentials configured - using live API")
                     else
                       logger.warn("USCIS API credentials not configured - using mock responses")
                   )
      
      // Create the gRPC service implementation with bounded ExecutionContext
      service    = new USCISCaseServiceGrpcImpl(persistence, httpClient, tokenCache, startTime)(ec)
      
      // Build the Armeria server
      server    <- Resource.make(
                     for {
                       _      <- logger.info(s"Starting unified server on ${config.host}:${config.port}")
                       _      <- logger.info("Protocols: gRPC (HTTP/2), gRPC-Web (HTTP/1.1), HTTP health")
                       server <- IO.blocking {
                                   buildServer(config, service)(ec)
                                 }
                       _      <- IO.blocking(server.start().join())
                       _      <- logger.info(s"Server started successfully on ${config.host}:${config.port}")
                       _      <- logger.info("Health endpoints: /health, /healthz, /ready, /live")
                     } yield server
                   )(server =>
                     for {
                       _  <- logger.info("Shutting down server...")
                       _  <- IO.blocking(server.stop().join())
                       _  <- logger.info("Server shutdown complete")
                     } yield ()
                   )
    } yield server
  }

  /** Overload for backward compatibility - loads config internally */
  def resource(
    persistence: PersistenceManager,
    httpClient: Client[IO],
    tokenCache: Ref[IO, Option[USCISApiClient.CachedToken]]
  ): Resource[IO, Server] = {
    for {
      config <- Resource.eval(loadConfig)
      server <- resource(config, persistence, httpClient, tokenCache)
    } yield server
  }

  private def buildServer(cfg: ServerConfig, service: USCISCaseServiceGrpcImpl)(implicit ec: ExecutionContext): Server = {
    // Build gRPC service with gRPC-Web support
    val grpcService = GrpcService.builder()
      .addService(USCISCaseServiceGrpc.bindService(service, ec))
      .addService(ProtoReflectionService.newInstance())
      // Enable gRPC-Web support (allows HTTP/1.1 clients)
      .enableUnframedRequests(true)
      .useBlockingTaskExecutor(true)
      .build()

    // Reuse single HealthCheckService instance for all health endpoints
    val healthService = HealthCheckService.of()

    // Build the Armeria server
    Server.builder()
      .http(cfg.port)
      // gRPC service at root path
      .service(grpcService)
      // Health check endpoints - reuse same instance
      .service("/health", healthService)
      .service("/healthz", healthService)
      .service("/ready", healthService)
      .service("/readyz", healthService)
      .service("/live", healthService)
      .service("/livez", healthService)
      // Root endpoint for basic health check (HEAD / and GET /)
      .service("/", new HttpService {
        override def serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse = {
          HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "OK")
        }
      })
      // Graceful shutdown
      .gracefulShutdownTimeoutMillis(10000, 30000)
      .build()
  }

  /**
   * Create server with all dependencies initialized.
   * Accepts pre-loaded config to avoid double-loading.
   */
  def resourceWithPersistence(config: ServerConfig): Resource[IO, Server] =
    for {
      _          <- Resource.eval(PersistenceManager.initialize)
      httpClient <- USCISApiClient.httpClientResource
      tokenCache <- Resource.eval(USCISApiClient.createTokenCache(httpClient))
      server     <- resource(config, PersistenceManager, httpClient, tokenCache)
    } yield server

  /** Overload for backward compatibility - loads config internally */
  def resourceWithPersistence: Resource[IO, Server] =
    for {
      config <- Resource.eval(loadConfig)
      server <- resourceWithPersistence(config)
    } yield server
}
