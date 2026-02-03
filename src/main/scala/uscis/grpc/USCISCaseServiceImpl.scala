package uscis.grpc

import cats.effect.{IO, Ref}
import cats.syntax.all._
import fs2.Stream
import io.grpc.Metadata
import org.http4s.client.Client
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import uscis.persistence.PersistenceManager
import uscis.api.{USCISClient, USCISApiClient}
import uscis.models.{CaseStatus => ModelCaseStatus, CaseHistory => ModelCaseHistory}
import uscis.proto.service._
import com.google.protobuf.timestamp.Timestamp
import java.time.{Instant, LocalDateTime, ZoneId, ZoneOffset}
import java.time.format.DateTimeFormatter
import scala.concurrent.duration._
import io.circe.syntax._
import io.circe.parser._

/**
 * gRPC service implementation for USCIS Case Tracker.
 * 
 * Implements the USCISCaseService defined in service.proto.
 * Integrates with the official USCIS Developer API for case status checks.
 * 
 * NOTE: After regenerating proto files (sbt compile), update:
 * - toProtoStatus to use google.protobuf.Timestamp for lastUpdated
 * - fromProtoStatus to parse Timestamp instead of string
 * - watchCases to use UpdateType enum instead of string
 * - exportCases to return Stream[IO, ExportCasesResponse]
 * - importCases to accept Stream[IO, ImportCasesChunk]
 */
class USCISCaseServiceImpl(
  persistence: PersistenceManager,
  httpClient: Client[IO],
  tokenCache: Ref[IO, Option[USCISApiClient.CachedToken]],
  startTime: Instant,
  zoneId: ZoneId = ZoneId.of("UTC")
) extends USCISCaseServiceFs2Grpc[IO, Metadata] {

  private val logger: Logger[IO] = Slf4jLogger.getLoggerFromClass[IO](this.getClass)
  private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

  // ============================================================
  // Converters
  // ============================================================

  /** Convert LocalDateTime to protobuf Timestamp */
  private def toProtoTimestamp(ldt: LocalDateTime): Timestamp = {
    val instant = ldt.toInstant(ZoneOffset.UTC)
    Timestamp(instant.getEpochSecond, instant.getNano)
  }

  /** Convert protobuf Timestamp to LocalDateTime */
  private def fromProtoTimestamp(ts: Timestamp): LocalDateTime =
    LocalDateTime.ofInstant(Instant.ofEpochSecond(ts.seconds, ts.nanos), ZoneOffset.UTC)

  private def toProtoStatus(status: ModelCaseStatus): CaseStatus =
    CaseStatus(
      receiptNumber = status.receiptNumber,
      caseType = status.caseType,
      currentStatus = status.currentStatus,
      lastUpdated = Some(toProtoTimestamp(status.lastUpdated)),
      details = status.details
    )

  private def toProtoHistory(history: ModelCaseHistory): CaseHistory =
    CaseHistory(
      receiptNumber = history.receiptNumber,
      statusUpdates = history.statusUpdates.map(toProtoStatus)
    )

  private def fromProtoStatus(status: CaseStatus): IO[ModelCaseStatus] =
    IO.pure {
      val lastUpdated = status.lastUpdated
        .map(fromProtoTimestamp)
        .getOrElse(LocalDateTime.now(ZoneOffset.UTC))
      ModelCaseStatus(
        receiptNumber = status.receiptNumber,
        caseType = status.caseType,
        currentStatus = status.currentStatus,
        lastUpdated = lastUpdated,
        details = status.details
      )
    }

  // ============================================================
  // Service Methods
  // ============================================================

  override def addCase(request: AddCaseRequest, ctx: Metadata): IO[AddCaseResponse] =
    for {
      _      <- logger.info(s"AddCase request for: ${request.receiptNumber}")
      result <- if (request.fetchFromUscis) {
                  USCISClient.checkCaseStatus(httpClient, tokenCache, request.receiptNumber)
                    .flatTap(persistence.addOrUpdateCase)
                    .map(status => AddCaseResponse(success = true, caseStatus = Some(toProtoStatus(status))))
                    .handleError(e => AddCaseResponse(success = false, errorMessage = Some(e.getMessage)))
                } else {
                  for {
                    now <- IO.realTimeInstant.map(i => LocalDateTime.ofInstant(i, zoneId))
                    status = ModelCaseStatus(
                      receiptNumber = USCISClient.normalizeReceiptNumber(request.receiptNumber),
                      caseType = request.caseType.getOrElse("Unknown"),
                      currentStatus = request.currentStatus.getOrElse("Pending"),
                      lastUpdated = now,
                      details = request.details
                    )
                    _ <- persistence.addOrUpdateCase(status)
                  } yield AddCaseResponse(success = true, caseStatus = Some(toProtoStatus(status)))
                }.handleError(e => AddCaseResponse(success = false, errorMessage = Some(e.getMessage)))
    } yield result

  override def getCase(request: GetCaseRequest, ctx: Metadata): IO[GetCaseResponse] =
    for {
      _          <- logger.info(s"GetCase request for: ${request.receiptNumber}")
      maybeCase  <- persistence.getCaseByReceipt(request.receiptNumber)
      response    = maybeCase match {
                      case Some(history) => 
                        // When include_history is false, return only the latest status
                        val filteredHistory = if (request.includeHistory) history 
                                              else history.copy(statusUpdates = history.statusUpdates.take(1))
                        GetCaseResponse(found = true, caseHistory = Some(toProtoHistory(filteredHistory)))
                      case None => GetCaseResponse(found = false)
                    }
    } yield response

  override def listCases(request: ListCasesRequest, ctx: Metadata): IO[ListCasesResponse] = {
    // page_size=0 means use server default of 20, page=0 is treated as page 1
    val pageSize = if (request.pageSize <= 0) 20 else request.pageSize
    val page = if (request.page <= 0) 1 else request.page
    val offset = (page - 1) * pageSize
    val normalizedFilter = request.receiptFilter.map(_.toUpperCase)

    for {
      _          <- logger.info(s"ListCases request, filter: ${request.receiptFilter}")
      // Use paginated query instead of loading all cases into memory
      totalCount <- persistence.countCases(normalizedFilter)
      paged      <- persistence.getCases(normalizedFilter, offset, pageSize)
      totalPages  = Math.ceil(totalCount.toDouble / pageSize).toInt.max(1)
    } yield ListCasesResponse(
      cases = paged.map(toProtoHistory),
      totalCount = totalCount,
      page = page,
      totalPages = totalPages
    )
  }

  override def checkStatus(request: CheckStatusRequest, ctx: Metadata): IO[CheckStatusResponse] =
    for {
      _      <- logger.info(s"CheckStatus request for: ${request.receiptNumber}")
      result <- USCISClient.checkCaseStatus(httpClient, tokenCache, request.receiptNumber)
                  .flatTap { status =>
                    if (request.saveToDatabase) persistence.addOrUpdateCase(status)
                    else IO.unit
                  }
                  .map(status => CheckStatusResponse(success = true, caseStatus = Some(toProtoStatus(status))))
                  .handleError(e => CheckStatusResponse(success = false, errorMessage = Some(e.getMessage)))
    } yield result

  override def deleteCase(request: DeleteCaseRequest, ctx: Metadata): IO[DeleteCaseResponse] =
    for {
      _      <- logger.info(s"DeleteCase request for: ${request.receiptNumber}")
      result <- persistence.deleteCase(request.receiptNumber)
                  .map(_ => DeleteCaseResponse(success = true))
                  .handleError(e => DeleteCaseResponse(success = false, errorMessage = Some(e.getMessage)))
    } yield result

  // Server streaming export: emits cases in chunks
  override def exportCases(request: ExportCasesRequest, ctx: Metadata): Stream[IO, ExportCasesResponse] = {
    Stream.eval(logger.info("ExportCases streaming request")) >>
    Stream.eval(persistence.getAllCases).flatMap { cases =>
      val json = cases.asJson.spaces2
      val total = cases.map(_.statusUpdates.size).sum
      // Emit a single response with all data (could be chunked for large datasets)
      Stream.emit(ExportCasesResponse(
        jsonData = json,
        caseCount = cases.size,
        totalUpdates = total
      ))
    }
  }

  // Streaming import: receives chunks and assembles them
  override def importCases(request: Stream[IO, ImportCasesChunk], ctx: Metadata): IO[ImportCasesResponse] =
    (for {
      _         <- logger.info("ImportCases streaming request")
      // Collect all chunks and concatenate json data
      allChunks <- request.compile.toList
      jsonData   = allChunks.map(_.jsonData).mkString
      result    <- decode[List[ModelCaseHistory]](jsonData) match {
                     case Right(cases) =>
                       persistence.saveCases(cases).map(_ => cases.size)
                     case Left(error) =>
                       IO.raiseError[Int](new RuntimeException(s"Parse error: ${error.getMessage}"))
                   }
    } yield ImportCasesResponse(success = true, importedCount = result))
      .handleError(e => ImportCasesResponse(success = false, errorMessage = Some(e.getMessage)))

  override def watchCases(request: WatchCasesRequest, ctx: Metadata): Stream[IO, CaseUpdate] = {
    val interval = if (request.pollIntervalSeconds <= 0) 60 else request.pollIntervalSeconds
    val receipts = request.receiptNumbers.toList

    Stream.eval(Ref.of[IO, Map[String, CaseStatus]](Map.empty)).flatMap { prevStatusRef =>
      Stream
        .awakeEvery[IO](interval.seconds)
        .evalMap { _ =>
          for {
            _         <- logger.debug("Polling for case updates")
            cases     <- if (receipts.isEmpty) persistence.getAllCases
                         else receipts.traverse(persistence.getCaseByReceipt).map(_.flatten)
            now       <- IO.realTimeInstant.map(_.toString)
            prevMap   <- prevStatusRef.get
            updates    = cases.flatMap(_.statusUpdates.headOption).map { status =>
                           val protoStatus = toProtoStatus(status)
                           val receipt = status.receiptNumber
                           val prevStatusOpt = prevMap.get(receipt)
                           val updateType: UpdateType = prevStatusOpt match {
                             case Some(prev) if prev != protoStatus => UpdateType.UPDATE_TYPE_CHANGED
                             case None => UpdateType.UPDATE_TYPE_NEW
                             case _ => UpdateType.UPDATE_TYPE_UNCHANGED
                           }
                           CaseUpdate(
                             caseStatus = Some(protoStatus),
                             updateType = updateType,
                             timestamp = now
                           )
                         }
            // Update the previous status map with current statuses
            currentMap: Map[String, CaseStatus] = cases.flatMap(_.statusUpdates.headOption).map { status =>
                           status.receiptNumber -> toProtoStatus(status)
                         }.toMap
            _         <- prevStatusRef.set(currentMap)
          } yield updates
        }
        .flatMap(updates => Stream.emits(updates))
    }
  }

  /** Application version - reads from BuildInfo if available, otherwise fallback */
  private val appVersion: String = 
    scala.util.Try(Class.forName("uscis.BuildInfo").getMethod("version").invoke(null).asInstanceOf[String])
      .getOrElse("0.1.0-SNAPSHOT")

  override def healthCheck(request: HealthCheckRequest, ctx: Metadata): IO[HealthCheckResponse] =
    for {
      now    <- IO.realTimeInstant
      cases  <- persistence.countCases
      uptime  = java.time.Duration.between(startTime, now).getSeconds
    } yield HealthCheckResponse(
      healthy = true,
      version = appVersion,
      uptimeSeconds = uptime,
      trackedCases = cases
    )
}
