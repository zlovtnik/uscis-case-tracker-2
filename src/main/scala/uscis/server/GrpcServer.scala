package uscis.server

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._
import io.grpc.{Server, ServerBuilder}
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.protobuf.services.{HealthStatusManager, ProtoReflectionService}
import io.grpc.health.v1.HealthCheckResponse.ServingStatus
import org.http4s.client.Client
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import uscis.grpc.USCISCaseServiceImpl
import uscis.proto.service.USCISCaseServiceFs2Grpc
import uscis.persistence.PersistenceManager
import uscis.api.{USCISApiClient, USCISConfig}
import com.typesafe.config.ConfigFactory
import java.time.Instant
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

/**
 * gRPC Server configuration and lifecycle management.
 * 
 * Integrates with the USCIS Developer API for case status checks.
 * Configuration is loaded lazily within the Resource to ensure errors
 * are handled in the controlled startup effect.
 */
object GrpcServer {

  /** Configuration case class for gRPC server settings */
  case class ServerConfig(host: String, port: Int)

  /** Load configuration within IO for controlled error handling */
  def loadConfig: IO[ServerConfig] = IO.delay {
    val config = ConfigFactory.load()
    ServerConfig(
      host = config.getString("grpc.host"),
      port = config.getInt("grpc.port")
    )
  }

  /**
   * Create a managed gRPC server resource.
   * 
   * The server will be automatically started and shutdown.
   * Includes HTTP client for USCIS API integration.
   */
  def resource(
    persistence: PersistenceManager,
    httpClient: Client[IO],
    tokenCache: Ref[IO, Option[USCISApiClient.CachedToken]]
  ): Resource[IO, Server] = {
    for {
      cfg       <- Resource.eval(loadConfig)
      logger    <- Resource.eval(Slf4jLogger.create[IO])
      startTime <- Resource.eval(IO.realTimeInstant)
      _         <- Resource.eval(
                     if (USCISConfig.hasCredentials) 
                       logger.info("USCIS API credentials configured - using live API")
                     else 
                       logger.warn("USCIS API credentials not configured - using mock responses")
                   )
      service    = new USCISCaseServiceImpl(persistence, httpClient, tokenCache, startTime)
      serviceDef <- USCISCaseServiceFs2Grpc.bindServiceResource[IO](service)
      
      // gRPC Health Check service for Kubernetes/Docker health probes
      healthManager = new HealthStatusManager()
      _         <- Resource.eval(IO.delay {
                     // Set overall server health status
                     healthManager.setStatus("", ServingStatus.SERVING)
                     // Set service-specific health status
                     healthManager.setStatus("uscis.USCISCaseService", ServingStatus.SERVING)
                   })
      
      server    <- Resource.make(
                     for {
                       _      <- logger.info(s"Starting gRPC server on ${cfg.host}:${cfg.port}")
                       server <- IO.blocking {
                                   NettyServerBuilder
                                     .forAddress(new InetSocketAddress(cfg.host, cfg.port))
                                     .addService(serviceDef)
                                     .addService(healthManager.getHealthService)
                                     .addService(ProtoReflectionService.newInstance())
                                     .build()
                                     .start()
                                 }
                       _      <- logger.info(s"gRPC server started successfully on ${cfg.host}:${cfg.port}")
                     } yield server
                   )(server =>
                     for {
                       _          <- logger.info("Shutting down gRPC server...")
                       _          <- IO.delay(healthManager.setStatus("", ServingStatus.NOT_SERVING))
                       _          <- IO.blocking(server.shutdown())
                       terminated <- IO.blocking(server.awaitTermination(30, TimeUnit.SECONDS))
                       _          <- if (!terminated) 
                                       IO.blocking(server.shutdownNow()) *> 
                                       logger.warn("Forced gRPC server shutdown after timeout")
                                     else 
                                       IO.unit
                       _          <- logger.info("gRPC server shutdown complete")
                     } yield ()
                   )
    } yield server
  }

  /**
   * Create server with all dependencies initialized.
   * 
   * Includes:
   * - PersistenceManager for local storage
   * - HTTP client for USCIS API calls
   * - OAuth2 token cache for authentication
   */
  def resourceWithPersistence: Resource[IO, Server] =
    for {
      _          <- Resource.eval(PersistenceManager.initialize)
      httpClient <- USCISApiClient.httpClientResource
      tokenCache <- Resource.eval(USCISApiClient.createTokenCache(httpClient))
      server     <- resource(PersistenceManager, httpClient, tokenCache)
    } yield server
}
