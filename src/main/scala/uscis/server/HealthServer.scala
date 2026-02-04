package uscis.server

import cats.effect.{IO, Resource}
import com.comcast.ip4s._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import org.typelevel.log4cats.slf4j.Slf4jLogger
import com.typesafe.config.ConfigFactory

/**
 * Simple HTTP health check server for cloud platform compatibility.
 * 
 * Cloud platforms (Render, Railway, Fly.io, etc.) typically probe HTTP endpoints
 * to verify service health. Since gRPC uses HTTP/2 and doesn't respond to HTTP/1.x
 * probes, we run a lightweight HTTP server on a separate port.
 * 
 * Default port: 8080 (configurable via HEALTH_PORT env var)
 */
object HealthServer {

  case class HealthConfig(port: Int)

  def loadConfig: IO[HealthConfig] = IO.delay {
    val config = ConfigFactory.load()
    HealthConfig(
      port = if (config.hasPath("health.port")) config.getInt("health.port") else 8080
    )
  }

  private val healthRoutes = HttpRoutes.of[IO] {
    // Root endpoint for basic health check
    case GET -> Root => 
      Ok("OK")
    
    // Standard health endpoint
    case HEAD -> Root =>
      Ok()
    
    // Kubernetes-style health endpoints
    case GET -> Root / "health" => 
      Ok("""{"status":"healthy"}""")
    
    case GET -> Root / "healthz" => 
      Ok("ok")
    
    case GET -> Root / "ready" => 
      Ok("ready")
    
    case GET -> Root / "readyz" => 
      Ok("ready")
    
    case GET -> Root / "live" => 
      Ok("live")
    
    case GET -> Root / "livez" => 
      Ok("live")
  }

  /**
   * Create a managed HTTP health check server resource.
   */
  def resource: Resource[IO, Server] = {
    for {
      cfg    <- Resource.eval(loadConfig)
      logger <- Resource.eval(Slf4jLogger.create[IO])
      _      <- Resource.eval(logger.info(s"Starting HTTP health server on port ${cfg.port}"))
      server <- EmberServerBuilder
                  .default[IO]
                  .withHost(host"0.0.0.0")
                  .withPort(Port.fromInt(cfg.port).getOrElse(port"8080"))
                  .withHttpApp(healthRoutes.orNotFound)
                  .withShutdownTimeout(scala.concurrent.duration.Duration.Zero)
                  .build
      _      <- Resource.eval(logger.info(s"HTTP health server started on port ${cfg.port}"))
    } yield server
  }
}
