package uscis

import cats.effect.{IO, IOApp, ExitCode, Resource}
import cats.syntax.all._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.slf4j.bridge.SLF4JBridgeHandler
import java.util.logging.Level
import uscis.server.ArmeriaServer

/**
 * USCIS Case Tracker gRPC Server.
 * 
 * A functional gRPC service for tracking USCIS case statuses.
 * Uses Cats Effect for pure functional effect management.
 * 
 * Runs a unified Armeria server on a single port supporting:
 * - gRPC (HTTP/2) for API clients
 * - gRPC-Web (HTTP/1.1) for browser clients  
 * - HTTP health endpoints for cloud platform health checks
 * 
 * The server runs until interrupted (SIGINT/SIGTERM).
 */
object Main extends IOApp {

  // Initialize logging configuration before anything else
  private def initLogging: IO[Unit] = IO.delay {
    // Force use of standard JDK XML parser instead of Oracle's restrictive parser
    // This prevents logback configuration errors when Oracle JDBC driver is present
    System.setProperty("javax.xml.parsers.SAXParserFactory", "com.sun.org.apache.xerces.internal.jaxp.SAXParserFactoryImpl")
    System.setProperty("javax.xml.parsers.DocumentBuilderFactory", "com.sun.org.apache.xerces.internal.jaxp.DocumentBuilderFactoryImpl")
    
    // Install JUL-to-SLF4J bridge for non-shaded classes
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()
    
    // Suppress Armeria/Netty verbose logs
    java.util.logging.Logger.getLogger("com.linecorp.armeria").setLevel(Level.WARNING)
    java.util.logging.Logger.getLogger("io.netty").setLevel(Level.WARNING)
  }

  override def run(args: List[String]): IO[ExitCode] = {
    val program = for {
      _      <- initLogging
      logger <- Slf4jLogger.create[IO]
      config <- ArmeriaServer.loadConfig
      _      <- logger.info("=" * 60)
      _      <- logger.info(s"USCIS Case Tracker gRPC Server v${BuildInfo.version}")
      _      <- logger.info("=" * 60)
      _      <- logger.info(s"Starting on port ${config.port}")
      _      <- logger.info("Protocols: gRPC, gRPC-Web, HTTP")
      // Pass pre-loaded config to avoid double-loading
      result <- ArmeriaServer.resourceWithPersistence(config)
                  .use { server =>
                    for {
                      _ <- logger.info("Server is ready to accept connections")
                      _ <- logger.info("Health endpoints: /health, /ready, /live")
                      _ <- logger.info("Press Ctrl+C to shutdown")
                      _ <- IO.never[Unit] // Keep running until interrupted
                    } yield ExitCode.Success
                  }
    } yield result

    program.handleErrorWith { error =>
      Slf4jLogger.create[IO]
        .flatMap(_.error(error)(s"Server failed: ${error.getMessage}"))
        .handleErrorWith(_ => IO(println(s"Server failed: ${error.getMessage}"))) *>
      IO.pure(ExitCode.Error)
    }
  }
}
