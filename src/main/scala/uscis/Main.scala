package uscis

import cats.effect.{IO, IOApp, ExitCode, Resource}
import cats.syntax.all._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.slf4j.bridge.SLF4JBridgeHandler
import java.util.logging.{Level, LogManager}
import uscis.server.GrpcServer

/**
 * USCIS Case Tracker gRPC Server.
 * 
 * A functional gRPC service for tracking USCIS case statuses.
 * Uses Cats Effect for pure functional effect management.
 * 
 * The server runs until interrupted (SIGINT/SIGTERM).
 */
object Main extends IOApp {

  // Initialize logging configuration before anything else
  // Suppresses noisy gRPC/Netty transport logs from HTTP/1.x health checks
  private def initLogging: IO[Unit] = IO.delay {
    // Install JUL-to-SLF4J bridge for non-shaded classes
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()
    
    // Suppress shaded Netty transport logs directly via JUL
    // These bypass the SLF4J bridge because they're relocated classes
    val nettyTransportLogger = java.util.logging.Logger.getLogger(
      "io.grpc.netty.shaded.io.grpc.netty.NettyServerTransport"
    )
    nettyTransportLogger.setLevel(Level.SEVERE)
  }

  override def run(args: List[String]): IO[ExitCode] = {
    val program = for {
      _      <- initLogging
      logger <- Slf4jLogger.create[IO]
      config <- GrpcServer.loadConfig
      _      <- logger.info("=" * 60)
      _      <- logger.info("USCIS Case Tracker gRPC Server v0.1.0")
      _      <- logger.info("=" * 60)
      _      <- logger.info(s"Starting server on ${config.host}:${config.port}")
      result <- GrpcServer.resourceWithPersistence
                  .use { server =>
                    for {
                      _ <- logger.info("Server is ready to accept connections")
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
