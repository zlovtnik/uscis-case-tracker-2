package uscis.persistence

import cats.effect.{IO, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all._
import io.circe._
import io.circe.syntax._
import io.circe.parser._
import uscis.models._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.nio.charset.StandardCharsets
import scala.io.Source

/**
 * Persistence manager using Cats Effect IO for all file operations.
 * 
 * All operations are pure and suspended in IO, providing:
 * - Referential transparency
 * - Composability with other IO operations
 * - Proper resource management
 * - Error handling via IO's error channel
 */
trait PersistenceManager {
  def initialize: IO[Unit]
  def loadCases: IO[List[CaseHistory]]
  def saveCases(cases: List[CaseHistory]): IO[Unit]
  def addOrUpdateCase(caseStatus: CaseStatus): IO[Unit]
  def getCaseByReceipt(receiptNumber: String): IO[Option[CaseHistory]]
  def getAllCases: IO[List[CaseHistory]]
  def deleteCase(receiptNumber: String): IO[Unit]
  def exportToFile(filepath: String): IO[(Int, Int)]
  def importFromFile(filepath: String): IO[Int]
  def caseExists(receiptNumber: String): IO[Boolean]
  def countCases: IO[Int]
  
  /** Get cases with optional filter and pagination.
   *  @param filter Optional substring filter for receipt numbers (applied case-insensitively)
   *  @param offset Number of records to skip (for pagination)
   *  @param limit Maximum number of records to return
   *  @return Paginated list of case histories
   */
  def getCases(filter: Option[String], offset: Int, limit: Int): IO[List[CaseHistory]]
  
  /** Count cases matching an optional filter.
   *  @param filter Optional substring filter for receipt numbers (applied case-insensitively)
   *  @return Count of matching cases
   */
  def countCases(filter: Option[String]): IO[Int]

  /** Get cases by a list of receipt numbers.
   *  Filters at the data layer instead of loading all cases into memory.
   *  @param receiptNumbers List of exact receipt numbers to match
   *  @return List of case histories matching any of the provided receipt numbers
   */
  def getCasesByReceipts(receiptNumbers: List[String]): IO[List[CaseHistory]]
}

object PersistenceManager extends PersistenceManager {

  private val dataDir: Path = Paths.get(System.getProperty("user.home"), ".uscis-tracker")
  private val caseFile: Path = dataDir.resolve("cases.json")

  /** Cached logger instance - created once and reused */
  private val logger: Logger[IO] = Slf4jLogger.getLoggerFromClass[IO](this.getClass)
  
  /** Mutex for synchronizing read-modify-write operations */
  private val mutex: IO[Semaphore[IO]] = Semaphore[IO](1)

  /** Resource for reading the case file */
  private def caseFileSource: Resource[IO, Source] =
    Resource.make(
      IO.blocking(Source.fromFile(caseFile.toFile, "UTF-8"))
    )(source => IO.blocking(source.close()))

  /** Initialize data directory and files */
  override def initialize: IO[Unit] =
    for {
      dirExists  <- IO.blocking(Files.exists(dataDir))
      _          <- IO.unlessA(dirExists) {
                      IO.blocking(Files.createDirectories(dataDir)) *>
                      logger.info(s"Created data directory: $dataDir")
                    }
      fileExists <- IO.blocking(Files.exists(caseFile))
      _          <- IO.unlessA(fileExists) {
                      IO.blocking(Files.write(caseFile, "[]".getBytes(StandardCharsets.UTF_8))) *>
                      logger.info(s"Created case file: $caseFile")
                    }
    } yield ()

  /** Load all cases from the JSON file */
  override def loadCases: IO[List[CaseHistory]] =
    caseFileSource.use { source =>
      IO.blocking {
        val content = source.mkString
        if (content.trim.isEmpty) {
          List.empty
        } else {
          decode[List[CaseHistory]](content) match {
            case Right(cases) => cases
            case Left(error)  => throw new RuntimeException(s"Failed to parse cases: ${error.getMessage}")
          }
        }
      }
    }

  /** Save all cases to the JSON file */
  override def saveCases(cases: List[CaseHistory]): IO[Unit] =
    for {
      json <- IO.pure(cases.asJson.spaces2)
      _    <- IO.blocking {
                Files.write(
                  caseFile,
                  json.getBytes(StandardCharsets.UTF_8),
                  StandardOpenOption.CREATE,
                  StandardOpenOption.WRITE,
                  StandardOpenOption.TRUNCATE_EXISTING
                )
              }
      _    <- logger.debug(s"Saved ${cases.size} case(s) to disk")
    } yield ()

  /** Add or update a case */
  override def addOrUpdateCase(caseStatus: CaseStatus): IO[Unit] =
    mutex.flatMap { sem =>
      sem.permit.use { _ =>
        for {
          cases <- loadCases
          updatedCases = cases.find(_.receiptNumber == caseStatus.receiptNumber) match {
            case Some(existing) =>
              // Update existing case by prepending new status
              val updated = existing.addStatus(caseStatus)
              updated :: cases.filterNot(_.receiptNumber == caseStatus.receiptNumber)
            case None =>
              // Add new case
              CaseHistory.create(caseStatus) :: cases
          }
          _ <- saveCases(updatedCases)
        } yield ()
      }
    }

  /** Get case by receipt number */
  override def getCaseByReceipt(receiptNumber: String): IO[Option[CaseHistory]] =
    loadCases.map(_.find(_.receiptNumber == receiptNumber))

  /** Get all cases */
  override def getAllCases: IO[List[CaseHistory]] = loadCases

  /** Delete a case by receipt number */
  override def deleteCase(receiptNumber: String): IO[Unit] =
    for {
      _   <- mutex.flatMap { sem =>
               sem.permit.use { _ =>
                 for {
                   cases <- loadCases
                   _     <- IO.raiseUnless(cases.exists(_.receiptNumber == receiptNumber))(
                              new NoSuchElementException(s"Receipt not found: $receiptNumber")
                            )
                   filtered = cases.filterNot(_.receiptNumber == receiptNumber)
                   _     <- saveCases(filtered)
                 } yield ()
               }
             }
      _   <- logger.info(s"Deleted case: $receiptNumber")
    } yield ()

  /** Export cases to a JSON file. Returns (caseCount, totalStatusUpdates) */
  override def exportToFile(filepath: String): IO[(Int, Int)] =
    for {
      cases        <- loadCases
      json          = cases.asJson.spaces2
      _            <- IO.blocking(Files.write(Paths.get(filepath), json.getBytes(StandardCharsets.UTF_8)))
      _            <- logger.info(s"Exported to: $filepath")
      caseCount     = cases.size
      totalUpdates  = cases.map(_.statusUpdates.size).sum
    } yield (caseCount, totalUpdates)

  /** Import cases from a JSON file */
  override def importFromFile(filepath: String): IO[Int] = {
    val fileResource = Resource.make(
      IO.blocking(Source.fromFile(filepath, "UTF-8"))
    )(source => IO.blocking(source.close()))

    for {
      cases <- fileResource.use { source =>
                 IO.blocking {
                   val content = source.mkString
                   decode[List[CaseHistory]](content) match {
                     case Right(parsed) => parsed
                     case Left(error)   => throw new RuntimeException(s"Failed to parse import file: ${error.getMessage}")
                   }
                 }
               }
      _     <- saveCases(cases)
      _     <- logger.info(s"Imported ${cases.size} case(s) from: $filepath")
    } yield cases.size
  }

  /** Check if a case exists */
  override def caseExists(receiptNumber: String): IO[Boolean] =
    loadCases.map(_.exists(_.receiptNumber == receiptNumber))

  /** Count all cases */
  override def countCases: IO[Int] = loadCases.map(_.size)

  /** Get cases with optional filter and pagination */
  override def getCases(filter: Option[String], offset: Int, limit: Int): IO[List[CaseHistory]] =
    loadCases.map { cases =>
      val filtered = filter match {
        case Some(f) => cases.filter(_.receiptNumber.toUpperCase.contains(f.toUpperCase))
        case None    => cases
      }
      filtered.drop(offset).take(limit)
    }

  /** Count cases matching an optional filter */
  override def countCases(filter: Option[String]): IO[Int] =
    loadCases.map { cases =>
      filter match {
        case Some(f) => cases.count(_.receiptNumber.toUpperCase.contains(f.toUpperCase))
        case None    => cases.size
      }
    }

  /** Get cases by a list of exact receipt numbers */
  override def getCasesByReceipts(receiptNumbers: List[String]): IO[List[CaseHistory]] =
    loadCases.map { cases =>
      if (receiptNumbers.isEmpty) cases
      else {
        val receiptSet = receiptNumbers.toSet
        cases.filter(c => receiptSet.contains(c.receiptNumber))
      }
    }
}
