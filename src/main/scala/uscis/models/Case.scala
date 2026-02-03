package uscis.models

import cats.syntax.all._
import cats.effect.IO
import io.circe._
import io.circe.generic.semiauto._
import java.time.LocalDateTime
import java.time.format.{DateTimeFormatter, DateTimeParseException}

case class CaseStatus(
  receiptNumber: String,
  caseType: String,
  currentStatus: String,
  lastUpdated: LocalDateTime,
  details: Option[String] = None
)

object CaseStatus {
  private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
  
  implicit val localDateTimeEncoder: Encoder[LocalDateTime] = 
    Encoder.encodeString.contramap[LocalDateTime](_.format(formatter))
  
  implicit val localDateTimeDecoder: Decoder[LocalDateTime] = 
    Decoder.decodeString.emap { s =>
      try {
        Right(LocalDateTime.parse(s, formatter))
      } catch {
        case e: DateTimeParseException => 
          Left(s"Invalid datetime format: '$s'. Expected ISO format (e.g., 2024-01-15T10:30:00). Error: ${e.getMessage}")
      }
    }
  
  implicit val caseStatusEncoder: Encoder[CaseStatus] = deriveEncoder[CaseStatus]
  implicit val caseStatusDecoder: Decoder[CaseStatus] = deriveDecoder[CaseStatus]
  
  /** Create a CaseStatus with the current timestamp */
  def create(
    receiptNumber: String,
    caseType: String,
    currentStatus: String,
    details: Option[String] = None
  ): IO[CaseStatus] = 
    IO.realTimeInstant.map { instant =>
      CaseStatus(
        receiptNumber = receiptNumber,
        caseType = caseType,
        currentStatus = currentStatus,
        lastUpdated = LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault()),
        details = details
      )
    }
}

case class CaseHistory(
  receiptNumber: String,
  statusUpdates: List[CaseStatus]
) {
  /** Get the latest status update */
  def latestStatus: Option[CaseStatus] = statusUpdates.headOption
  
  /** Add a new status update to the history */
  def addStatus(status: CaseStatus): CaseHistory = 
    copy(statusUpdates = status :: statusUpdates)
  
  /** Validate that all status updates belong to this case */
  def validateReceiptNumbers(): Either[String, CaseHistory] = {
    val distinctReceipts = statusUpdates.map(_.receiptNumber).distinct
    if (distinctReceipts.isEmpty) {
      Right(this)
    } else if (distinctReceipts.size == 1 && distinctReceipts.head == receiptNumber) {
      Right(this)
    } else {
      val mismatched = statusUpdates
        .filter(_.receiptNumber != receiptNumber)
        .map(_.receiptNumber)
        .distinct
      Left(s"Receipt number mismatch in CaseHistory($receiptNumber): found $mismatched in statusUpdates")
    }
  }
  
  /** Validate as an IO effect */
  def validate: IO[CaseHistory] = 
    IO.fromEither(validateReceiptNumbers().leftMap(new RuntimeException(_)))
}

object CaseHistory {
  implicit val caseHistoryEncoder: Encoder[CaseHistory] = deriveEncoder[CaseHistory]
  implicit val caseHistoryDecoder: Decoder[CaseHistory] = deriveDecoder[CaseHistory]
  
  /** Create a new case history with initial status */
  def create(status: CaseStatus): CaseHistory = 
    CaseHistory(status.receiptNumber, List(status))
  
  /** Factory method with validation */
  def validated(receiptNumber: String, statusUpdates: List[CaseStatus]): Either[String, CaseHistory] = {
    val history = CaseHistory(receiptNumber, statusUpdates)
    history.validateReceiptNumbers()
  }
  
  /** Factory method with validation as IO */
  def validatedIO(receiptNumber: String, statusUpdates: List[CaseStatus]): IO[CaseHistory] = 
    IO.fromEither(validated(receiptNumber, statusUpdates).leftMap(new RuntimeException(_)))
}
