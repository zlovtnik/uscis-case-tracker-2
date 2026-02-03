package uscis.api

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._
import cats.effect.std.Semaphore
import uscis.models.CaseStatus
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.http4s.client.Client
import java.time.LocalDateTime
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._
import java.time.Instant

/**
 * Utility for consistent receipt number masking in logs.
 * Masks the middle portion of receipt numbers for privacy.
 */
object ReceiptMasker {
  def mask(receipt: String): String = {
    if (receipt.length >= 7) {
      s"${receipt.take(3)}****${receipt.takeRight(4)}"
    } else if (receipt.length >= 3) {
      s"${receipt.take(3)}****"
    } else {
      "****"
    }
  }
}

/**
 * Shared configuration holder for USCIS API settings.
 * Loads configuration from application.conf.
 * Credentials are accessed on-demand to minimize secret exposure time.
 */
object USCISConfig {
  private val config = ConfigFactory.load()

  val baseUrl: String = config.getString("uscis.api.base-url")
  val tokenUrl: String = config.getString("uscis.api.oauth.token-url")
  /** On-demand accessor for client ID - reads from config each time */
  def clientId: String = config.getString("uscis.api.oauth.client-id")
  /** On-demand accessor for client secret - minimizes secret exposure time */
  def clientSecret: String = config.getString("uscis.api.oauth.client-secret")
  val timeout: Int = config.getInt("uscis.api.timeout")
  val sandboxMode: Boolean = config.getBoolean("uscis.api.sandbox-mode")
  val minRequestIntervalMs: Int = config.getInt("uscis.api.rate-limit.min-request-interval-ms")
  val maxRetries: Int = config.getInt("uscis.check.max-retries")
  val batchDelay: FiniteDuration = config.getInt("uscis.check.batch-delay").millis
  val maxConcurrency: Int = config.getInt("uscis.check.max-concurrency")

  /** Check if OAuth credentials are configured */
  def hasCredentials: Boolean = clientId.nonEmpty && clientSecret.nonEmpty
}

/**
 * USCIS Case Status Client using Cats Effect IO.
 *
 * Integrates with the official USCIS Developer API:
 * - Sandbox: https://api-int.uscis.gov/case-status
 * - OAuth2 Client Credentials authentication
 * - Rate limiting support (10 TPS / 100ms between requests)
 * 
 * All operations are pure and suspended in IO.
 * 
 * @see https://developer.uscis.gov
 */
object USCISClient {

  /** Cached logger instance - created once and reused */
  private val logger: Logger[IO] = Slf4jLogger.getLoggerFromClass[IO](this.getClass)
  private val receiptNumberPattern = "[A-Z]{3}\\d{10}".r

  /** Shared rate limiter - tracks last request timestamp in milliseconds */
  private val lastRequestTimeRef: IO[Ref[IO, Long]] = Ref.of[IO, Long](0L)

  /**
   * Rate limiter helper that ensures minimum interval between requests.
   * Computes required wait time, sleeps only if needed, then runs the action.
   */
  private def withRateLimit[A](minIntervalMs: Long)(action: IO[A]): IO[A] =
    for {
      ref       <- lastRequestTimeRef
      now       <- IO.realTime.map(_.toMillis)
      lastTime  <- ref.get
      waitTime   = math.max(0L, minIntervalMs - (now - lastTime))
      _         <- IO.whenA(waitTime > 0)(IO.sleep(waitTime.millis))
      newTime   <- IO.realTime.map(_.toMillis)
      _         <- ref.set(newTime)
      result    <- action
    } yield result

  /** Validate receipt number format */
  def validateReceiptNumber(receiptNumber: String): IO[Unit] =
    IO.raiseUnless(receiptNumberPattern.matches(receiptNumber))(
      new IllegalArgumentException(
        s"Invalid receipt number format: $receiptNumber. Expected format: 3 letters + 10 digits"
      )
    )

  /** Normalize receipt number from various formats */
  def normalizeReceiptNumber(input: String): String =
    input.toUpperCase.replaceAll("[^A-Z0-9]", "")

  /**
   * Check the status of a single case using the USCIS API.
   * 
   * If API credentials are not configured, returns a mock response.
   * Rate limiting is applied (100ms minimum between requests).
   *
   * @param client HTTP client for API requests
   * @param tokenCache OAuth2 token cache for authentication
   * @param receiptNumber The USCIS receipt number to check
   * @return The current case status
   */
  def checkCaseStatus(
    client: Client[IO],
    tokenCache: Ref[IO, Option[USCISApiClient.CachedToken]],
    receiptNumber: String
  ): IO[CaseStatus] =
    for {
      _      <- logger.debug(s"Checking status for case: ${ReceiptMasker.mask(receiptNumber)}")
      _      <- validateReceiptNumber(receiptNumber)
      // Rate limiting: minimum 100ms between requests using shared rate limiter
      status <- withRateLimit(USCISConfig.minRequestIntervalMs.toLong) {
                  USCISApiClient.checkCaseStatus(client, tokenCache, receiptNumber)
                }
    } yield status

  /**
   * Check the status of a single case (standalone version).
   * Creates its own HTTP client - use the version with client parameter for efficiency.
   */
  def checkCaseStatus(receiptNumber: String): IO[CaseStatus] =
    USCISApiClient.httpClientResource.use { client =>
      for {
        tokenCache <- USCISApiClient.createTokenCache(client)
        status     <- checkCaseStatus(client, tokenCache, receiptNumber)
      } yield status
    }

  /**
   * Check multiple cases with rate limiting using a semaphore.
   * Respects USCIS API rate limits (10 TPS).
   *
   * @param client HTTP client for API requests
   * @param tokenCache OAuth2 token cache
   * @param receiptNumbers List of receipt numbers to check
   * @return Map of receipt number to Either error message or CaseStatus
   */
  def checkMultipleCases(
    client: Client[IO],
    tokenCache: Ref[IO, Option[USCISApiClient.CachedToken]],
    receiptNumbers: List[String]
  ): IO[Map[String, Either[Throwable, CaseStatus]]] =
    for {
      semaphore <- Semaphore[IO](USCISConfig.maxConcurrency.toLong)
      results   <- receiptNumbers.parTraverse { receipt =>
                     semaphore.permit.use { _ =>
                       checkCaseStatus(client, tokenCache, receipt)
                         .map(status => receipt -> Right(status))
                         .handleError(e => receipt -> Left(e))
                     }
                   }
    } yield results.toMap

  /**
   * Check multiple cases in batches with delays between batches.
   * Suitable for large numbers of cases while respecting rate limits.
   */
  def checkMultipleCasesBatched(
    client: Client[IO],
    tokenCache: Ref[IO, Option[USCISApiClient.CachedToken]],
    receiptNumbers: List[String]
  ): IO[Map[String, Either[Throwable, CaseStatus]]] = {
    val batches = receiptNumbers.grouped(USCISConfig.maxConcurrency).toList

    batches.zipWithIndex.foldLeftM(Map.empty[String, Either[Throwable, CaseStatus]]) {
      case (acc, (batch, idx)) =>
        for {
          batchResults <- batch.traverse { receipt =>
                            checkCaseStatus(client, tokenCache, receipt)
                              .map(status => receipt -> Right(status))
                              .handleError(e => receipt -> Left(e))
                          }
          _ <- IO.whenA(idx < batches.size - 1)(
                 IO.sleep(USCISConfig.batchDelay) *>
                 logger.debug(s"Completed batch ${idx + 1}/${batches.size}")
               )
        } yield acc ++ batchResults.toMap
    }
  }

  /**
   * Check a single case with exponential backoff retry logic.
   * Only retries on transient errors (network, timeout); permanent errors fail fast.
   */
  def checkCaseStatusWithRetry(
    client: Client[IO],
    tokenCache: Ref[IO, Option[USCISApiClient.CachedToken]],
    receiptNumber: String,
    maxRetries: Int = USCISConfig.maxRetries
  ): IO[CaseStatus] = {
    val baseDelay = 1.second
    val maxDelay = 30.seconds

    /** Determine if error is transient (retry-able) */
    def isTransientError(error: Throwable): Boolean = error match {
      case _: IllegalArgumentException => false  // Invalid receipt format
      case e if e.getMessage != null && (
        e.getMessage.contains("401") || 
        e.getMessage.contains("403") ||
        e.getMessage.contains("Invalid")
      ) => false  // Authentication or validation errors
      case _ => true  // Network, timeout, 5xx errors are transient
    }

    def retry(attemptsLeft: Int, attempt: Int): IO[CaseStatus] =
      checkCaseStatus(client, tokenCache, receiptNumber).handleErrorWith { error =>
        if (!isTransientError(error)) {
          // Permanent error - fail immediately
          logger.error(error)(
            s"Permanent error for ${ReceiptMasker.mask(receiptNumber)}, not retrying"
          ) *> IO.raiseError(error)
        } else if (attemptsLeft > 0) {
          // Transient error - retry with exponential backoff
          val delayDuration = (baseDelay * Math.pow(2, attempt - 1).toLong).min(maxDelay)
          logger.warn(
            s"Retry $attempt/$maxRetries for ${ReceiptMasker.mask(receiptNumber)} " +
            s"after ${delayDuration.toSeconds}s delay: ${error.getMessage}"
          ) *>
          IO.sleep(delayDuration) *>
          retry(attemptsLeft - 1, attempt + 1)
        } else {
          // Exhausted retries
          logger.error(error)(
            s"Exhausted $maxRetries retries for ${ReceiptMasker.mask(receiptNumber)}"
          ) *> IO.raiseError(error)
        }
      }

    retry(maxRetries, 1)
  }

  /**
   * Create HTTP client and token cache resources for API access.
   */
  def apiResources: Resource[IO, (Client[IO], Ref[IO, Option[USCISApiClient.CachedToken]])] =
    for {
      client     <- USCISApiClient.httpClientResource
      tokenCache <- Resource.eval(USCISApiClient.createTokenCache(client))
    } yield (client, tokenCache)
}
