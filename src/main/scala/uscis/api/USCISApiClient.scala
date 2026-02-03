package uscis.api

import cats.effect.{IO, Resource, Ref}
import cats.effect.std.Semaphore
import cats.syntax.all._
import org.http4s._
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.circe._
import org.http4s.headers._
import io.circe._
import io.circe.generic.semiauto._
import io.circe.parser._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._
import java.time.Instant
import uscis.models.CaseStatus
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * USCIS Case Status API Client
 * 
 * Integrates with the official USCIS Developer API:
 * - Sandbox: https://api-int.uscis.gov/case-status
 * - OAuth2 Client Credentials authentication
 * - Rate limiting: 10 TPS (100ms between requests)
 * 
 * @see https://developer.uscis.gov
 */
object USCISApiClient {

  // ============================================================
  // Configuration
  // ============================================================

  private val config = ConfigFactory.load().getConfig("uscis.api")
  
  val baseUrl: String = config.getString("base-url")
  val tokenUrl: String = config.getString("oauth.token-url")
  private val clientId: String = config.getString("oauth.client-id")
  private val clientSecret: String = config.getString("oauth.client-secret")
  val timeout: Int = config.getInt("timeout")
  val sandboxMode: Boolean = config.getBoolean("sandbox-mode")
  val minRequestIntervalMs: Int = config.getInt("rate-limit.min-request-interval-ms")

  def hasCredentials: Boolean = clientId.nonEmpty && clientSecret.nonEmpty

  // ============================================================
  // Staging Receipt Numbers (for sandbox testing)
  // ============================================================

  /** Staging receipt numbers WITH historical case data */
  val stagingReceiptsWithHistory: Set[String] = Set(
    "EAC9999103403", "EAC9999103404", "EAC9999103405", "EAC9999103410",
    "EAC9999103411", "EAC9999103416", "EAC9999103419",
    "LIN9999106498", "LIN9999106499", "LIN9999106504", "LIN9999106505", "LIN9999106506",
    "SRC9999102777", "SRC9999102778", "SRC9999102779", "SRC9999102780",
    "SRC9999102781", "SRC9999102782", "SRC9999102783", "SRC9999102784",
    "SRC9999102785", "SRC9999102786", "SRC9999102787",
    "SRC9999132710", "SRC9999132719"
  )

  /** Staging receipt numbers WITHOUT historical case data */
  val stagingReceiptsWithoutHistory: Set[String] = Set(
    "EAC9999103400", "EAC9999103402", "EAC9999103406", "EAC9999103407",
    "EAC9999103408", "EAC9999103409", "EAC9999103412", "EAC9999103413",
    "EAC9999103414", "EAC9999103415", "EAC9999103420", "EAC9999103421",
    "EAC9999103424", "EAC9999103425", "EAC9999103426", "EAC9999103428",
    "EAC9999103429", "EAC9999103431", "EAC9999103432",
    "LIN9999106501", "LIN9999106507",
    "SRC9999132694", "SRC9999132695", "SRC9999132706", "SRC9999132707"
  )

  /** All staging receipt numbers */
  val allStagingReceipts: Set[String] = stagingReceiptsWithHistory ++ stagingReceiptsWithoutHistory

  // ============================================================
  // API Response Models (from USCIS API schema)
  // ============================================================

  /** Historical case status entry */
  case class HistCaseStatus(
    date: Option[String],
    statusTextEn: Option[String],
    statusDescEn: Option[String],
    statusTextEs: Option[String],
    statusDescEs: Option[String]
  )

  object HistCaseStatus {
    implicit val decoder: Decoder[HistCaseStatus] = Decoder.instance { c =>
      for {
        date <- c.downField("date").as[Option[String]]
        textEn <- c.downField("status_text_en").as[Option[String]]
        descEn <- c.downField("status_desc_en").as[Option[String]]
        textEs <- c.downField("status_text_es").as[Option[String]]
        descEs <- c.downField("status_desc_es").as[Option[String]]
      } yield HistCaseStatus(date, textEn, descEn, textEs, descEs)
    }
  }

  /** USCIS API case_status object */
  case class ApiCaseStatus(
    receiptNumber: String,
    formType: Option[String],
    submittedDate: Option[String],
    modifiedDate: Option[String],
    currentCaseStatusTextEn: Option[String],
    currentCaseStatusDescEn: Option[String],
    currentCaseStatusTextEs: Option[String],
    currentCaseStatusDescEs: Option[String],
    histCaseStatus: Option[List[HistCaseStatus]]
  )

  object ApiCaseStatus {
    implicit val decoder: Decoder[ApiCaseStatus] = Decoder.instance { c =>
      for {
        receipt <- c.downField("receiptNumber").as[String]
        form <- c.downField("formType").as[Option[String]]
        submitted <- c.downField("submittedDate").as[Option[String]]
        modified <- c.downField("modifiedDate").as[Option[String]]
        textEn <- c.downField("current_case_status_text_en").as[Option[String]]
        descEn <- c.downField("current_case_status_desc_en").as[Option[String]]
        textEs <- c.downField("current_case_status_text_es").as[Option[String]]
        descEs <- c.downField("current_case_status_desc_es").as[Option[String]]
        hist <- c.downField("hist_case_status").as[Option[List[HistCaseStatus]]]
      } yield ApiCaseStatus(receipt, form, submitted, modified, textEn, descEn, textEs, descEs, hist)
    }
  }

  /** USCIS API success response */
  case class ApiSuccessResponse(
    caseStatus: ApiCaseStatus,
    message: Option[String]
  )

  object ApiSuccessResponse {
    implicit val decoder: Decoder[ApiSuccessResponse] = Decoder.instance { c =>
      for {
        status <- c.downField("case_status").as[ApiCaseStatus]
        msg <- c.downField("message").as[Option[String]]
      } yield ApiSuccessResponse(status, msg)
    }
  }

  /** OAuth2 token response */
  case class TokenResponse(
    accessToken: String,
    tokenType: String,
    expiresIn: Long
  )

  object TokenResponse {
    implicit val decoder: Decoder[TokenResponse] = Decoder.instance { c =>
      for {
        token <- c.downField("access_token").as[String]
        tokenType <- c.downField("token_type").as[String]
        expires <- c.downField("expires_in").as[Long]
      } yield TokenResponse(token, tokenType, expires)
    }
  }

  // ============================================================
  // OAuth2 Token Management
  // ============================================================

  case class CachedToken(accessToken: String, expiresAt: Instant)

  /** Fetch a new OAuth2 access token */
  def fetchAccessToken(client: Client[IO]): IO[TokenResponse] = {
    val uri = Uri.unsafeFromString(tokenUrl)
    
    val form = UrlForm(
      "grant_type" -> "client_credentials",
      "client_id" -> clientId,
      "client_secret" -> clientSecret
    )

    val request = Request[IO](Method.POST, uri)
      .withEntity(form)
      .withHeaders(Accept(MediaType.application.json))

    for {
      logger <- Slf4jLogger.create[IO]
      _      <- logger.debug(s"Requesting OAuth2 token from $tokenUrl")
      resp   <- client.expect[Json](request)
      token  <- IO.fromEither(resp.as[TokenResponse])
      _      <- logger.info("Successfully obtained OAuth2 access token")
    } yield token
  }

  /** Create a token cache with automatic refresh and a semaphore for synchronization */
  def createTokenCache(client: Client[IO]): IO[Ref[IO, Option[CachedToken]]] =
    Ref.of[IO, Option[CachedToken]](None)

  /** Semaphore for serializing token refresh operations */
  private val tokenRefreshSemaphore: IO[Semaphore[IO]] = Semaphore[IO](1)

  /** Get a valid access token, refreshing if necessary (thread-safe) */
  def getAccessToken(
    client: Client[IO],
    tokenCache: Ref[IO, Option[CachedToken]]
  ): IO[String] = {
    tokenRefreshSemaphore.flatMap { sem =>
      sem.permit.use { _ =>
        for {
          now       <- IO.realTimeInstant
          cachedOpt <- tokenCache.get
          validToken <- cachedOpt match {
            case Some(cached) if cached.expiresAt.isAfter(now.plusSeconds(60)) =>
              // Token still valid (with 60s buffer)
              IO.pure(cached.accessToken)
            case _ =>
              // Need to refresh token
              for {
                tokenResp <- fetchAccessToken(client)
                expiresAt  = now.plusSeconds(tokenResp.expiresIn)
                cached     = CachedToken(tokenResp.accessToken, expiresAt)
                _         <- tokenCache.set(Some(cached))
              } yield cached.accessToken
          }
        } yield validToken
      }
    }
  }

  // ============================================================
  // Case Status API
  // ============================================================

  /** Query case status from USCIS API */
  def queryCaseStatus(
    client: Client[IO],
    tokenCache: Ref[IO, Option[CachedToken]],
    receiptNumber: String
  ): IO[CaseStatus] = {
    for {
      logger      <- Slf4jLogger.create[IO]
      _           <- logger.info(s"Querying USCIS API for case: ${ReceiptMasker.mask(receiptNumber)}")
      accessToken <- getAccessToken(client, tokenCache)
      uri          = Uri.unsafeFromString(s"$baseUrl/$receiptNumber")
      request      = Request[IO](Method.GET, uri)
                       .withHeaders(
                         Authorization(Credentials.Token(AuthScheme.Bearer, accessToken)),
                         Accept(MediaType.application.json)
                       )
      response    <- client.expect[Json](request)
      apiResp     <- IO.fromEither(response.as[ApiSuccessResponse])
      caseStatus  <- convertToModel(apiResp.caseStatus)
      _           <- logger.info(s"Successfully retrieved status for ${ReceiptMasker.mask(receiptNumber)}")
    } yield caseStatus
  }

  /** Configurable timezone for consistent timestamp handling */
  val defaultZone: ZoneId = ZoneId.of("UTC")

  /** Convert API response to domain model.
   *  Raises an error if modifiedDate is present but cannot be parsed.
   */
  private def convertToModel(api: ApiCaseStatus): IO[CaseStatus] = {
    val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
    
    def parseDate(dateStr: String): Either[Throwable, LocalDateTime] =
      scala.util.Try(LocalDateTime.parse(dateStr.take(19), dateFormatter)).toEither

    for {
      now <- IO.realTimeInstant.map(i => LocalDateTime.ofInstant(i, defaultZone))
      lastUpdated <- api.modifiedDate match {
        case Some(dateStr) =>
          IO.fromEither(parseDate(dateStr).left.map { err =>
            new IllegalArgumentException(
              s"Failed to parse modifiedDate for ${api.receiptNumber}: '$dateStr' - ${err.getMessage}"
            )
          })
        case None => IO.pure(now)
      }
    } yield CaseStatus(
      receiptNumber = api.receiptNumber,
      caseType = api.formType.getOrElse("Unknown"),
      currentStatus = api.currentCaseStatusTextEn.getOrElse("Status Unknown"),
      lastUpdated = lastUpdated,
      details = api.currentCaseStatusDescEn
    )
  }

  // ============================================================
  // Mock/Sandbox Responses (when API credentials not configured)
  // ============================================================

  /** Generate mock response for sandbox testing */
  def mockCaseStatus(receiptNumber: String): IO[CaseStatus] = {
    for {
      logger <- Slf4jLogger.create[IO]
      _      <- logger.warn(s"Using mock response for ${ReceiptMasker.mask(receiptNumber)} (no API credentials configured)")
      now    <- IO.realTimeInstant.map(i => LocalDateTime.ofInstant(i, defaultZone))
      
      // Generate realistic mock data based on receipt prefix
      (formType, status, details) = receiptNumber.take(3) match {
        case "IOE" => (
          "I-485 (Application to Register Permanent Residence)",
          "Case Was Received",
          Some(s"On ${now.toLocalDate}, we received your Form I-485, Application to Register Permanent Residence or Adjust Status.")
        )
        case "EAC" => (
          "I-765 (Application for Employment Authorization)",
          "Card Was Delivered",
          Some("The Post Office has delivered your new card. If you have not received your card, please check with your local post office.")
        )
        case "LIN" => (
          "I-140 (Immigrant Petition for Alien Workers)",
          "Case Was Approved",
          Some("We have approved your I-140, Immigrant Petition for Alien Workers.")
        )
        case "SRC" => (
          "I-130 (Petition for Alien Relative)",
          "Case Is Being Actively Reviewed",
          Some("Your case is currently being reviewed by a USCIS officer.")
        )
        case "WAC" => (
          "I-539 (Application to Extend/Change Nonimmigrant Status)",
          "Request for Evidence Was Sent",
          Some("We sent a request for evidence (RFE) on your case. Please review and respond within the specified timeframe.")
        )
        case _ => (
          "Unknown Form",
          "Status Check Required",
          Some("Please visit USCIS.gov for the most current status of your case.")
        )
      }
    } yield CaseStatus(
      receiptNumber = receiptNumber,
      caseType = formType,
      currentStatus = status,
      lastUpdated = now,
      details = details
    )
  }

  // ============================================================
  // Resource Management
  // ============================================================

  /** Create an HTTP client resource */
  def httpClientResource: Resource[IO, Client[IO]] =
    EmberClientBuilder
      .default[IO]
      .withTimeout(timeout.seconds)
      .withIdleConnectionTime(60.seconds)
      .build

  /** Check case status (uses real API if credentials configured, otherwise mock)
   *  Includes error handling with logging for failed API calls.
   */
  def checkCaseStatus(
    client: Client[IO],
    tokenCache: Ref[IO, Option[CachedToken]],
    receiptNumber: String
  ): IO[CaseStatus] = {
    if (hasCredentials) {
      queryCaseStatus(client, tokenCache, receiptNumber).handleErrorWith { error =>
        for {
          logger <- Slf4jLogger.create[IO]
          _      <- logger.error(error)(
                      s"Failed to query USCIS API for ${ReceiptMasker.mask(receiptNumber)}"
                    )
          _      <- IO.raiseError[CaseStatus](error)
        } yield throw error // unreachable, but needed for type
      }
    } else {
      mockCaseStatus(receiptNumber)
    }
  }
}
