package uscis.persistence

import cats.effect.{IO, Resource}
import cats.syntax.all._
import doobie._
import doobie.implicits._
import doobie.implicits.javatimedrivernative._
import doobie.hikari.HikariTransactor
import com.zaxxer.hikari.HikariConfig
import uscis.models._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import java.time.LocalDateTime

/**
 * Oracle database configuration for wallet-based authentication.
 * 
 * Supports both:
 * - Cloud wallet (Oracle Autonomous Database) with TNS_ADMIN
 * - Standard JDBC with user/password
 * 
 * @param jdbcUrl JDBC URL, e.g., "jdbc:oracle:thin:@dbname_high?TNS_ADMIN=/path/to/wallet"
 * @param user Database username
 * @param password Database password
 * @param walletPath Path to Oracle wallet directory (containing cwallet.sso, tnsnames.ora, etc.)
 * @param trustStorePassword Password for the trust store (optional)
 * @param keyStorePassword Password for the key store (optional)
 * @param poolSize Maximum connection pool size
 */
case class OracleConfig(
  jdbcUrl: String,
  user: String,
  password: String,
  walletPath: Option[String],
  trustStorePassword: Option[String] = None,
  keyStorePassword: Option[String] = None,
  trustStoreFile: String = "truststore.jks",
  keyStoreFile: String = "keystore.jks",
  poolSize: Int = 10
)

/**
 * Oracle-backed persistence manager using Doobie for functional JDBC.
 * 
 * Features:
 * - HikariCP connection pooling
 * - Oracle wallet authentication support
 * - Automatic table creation
 * - Pure functional API with Cats Effect IO
 */
class OraclePersistenceManager(xa: Transactor[IO]) extends PersistenceManager {

  private val logger: Logger[IO] = Slf4jLogger.getLoggerFromClass[IO](this.getClass)

  // LocalDateTime is natively supported by doobie.implicits.javatimedrivernative._

  /** Initialize database tables */
  override def initialize: IO[Unit] = {
    // Oracle doesn't support IF NOT EXISTS, so we need to handle errors gracefully
    initializeOracle.transact(xa) *> logger.info("Oracle database initialized")
  }

  /** Oracle-specific initialization that handles existing tables */
  private def initializeOracle: ConnectionIO[Unit] = {
    val createCaseHistory = sql"""
      BEGIN
        EXECUTE IMMEDIATE 'CREATE TABLE case_history (
          receipt_number VARCHAR2(50) PRIMARY KEY,
          created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        )';
      EXCEPTION
        WHEN OTHERS THEN
          IF SQLCODE = -955 THEN NULL; ELSE RAISE; END IF; -- Table already exists
      END;
    """.update.run

    val createStatusUpdates = sql"""
      BEGIN
        EXECUTE IMMEDIATE 'CREATE TABLE status_updates (
          id NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
          receipt_number VARCHAR2(50) NOT NULL,
          case_type VARCHAR2(100) NOT NULL,
          current_status VARCHAR2(500) NOT NULL,
          last_updated TIMESTAMP NOT NULL,
          details CLOB,
          CONSTRAINT fk_receipt FOREIGN KEY (receipt_number) 
            REFERENCES case_history(receipt_number) ON DELETE CASCADE
        )';
      EXCEPTION
        WHEN OTHERS THEN
          IF SQLCODE = -955 THEN NULL; ELSE RAISE; END IF; -- Table already exists
      END;
    """.update.run

    val createIndex = sql"""
      BEGIN
        EXECUTE IMMEDIATE 'CREATE INDEX idx_status_receipt ON status_updates(receipt_number)';
      EXCEPTION
        WHEN OTHERS THEN
          IF SQLCODE IN (-955, -1408) THEN NULL; ELSE RAISE; END IF; -- Index already exists
      END;
    """.update.run

    for {
      _ <- createCaseHistory
      _ <- createStatusUpdates
      _ <- createIndex
    } yield ()
  }

  /** Load all cases from database */
  override def loadCases: IO[List[CaseHistory]] = getAllCases

  /** Save all cases (replaces all existing data) */
  override def saveCases(cases: List[CaseHistory]): IO[Unit] = {
    val program = for {
      _ <- sql"DELETE FROM status_updates".update.run
      _ <- sql"DELETE FROM case_history".update.run
      _ <- cases.traverse_ { history =>
        for {
          _ <- sql"""
            INSERT INTO case_history (receipt_number) VALUES (${history.receiptNumber})
          """.update.run
          _ <- history.statusUpdates.traverse_ { status =>
            sql"""
              INSERT INTO status_updates (receipt_number, case_type, current_status, last_updated, details)
              VALUES (${status.receiptNumber}, ${status.caseType}, ${status.currentStatus}, 
                      ${status.lastUpdated}, ${status.details})
            """.update.run
          }
        } yield ()
      }
    } yield ()

    program.transact(xa) *> logger.debug(s"Saved ${cases.size} case(s) to Oracle")
  }

  /** Add or update a case */
  override def addOrUpdateCase(caseStatus: CaseStatus): IO[Unit] = {
    val program = for {
      // Atomic upsert for case_history using MERGE
      _ <- sql"""
        MERGE INTO case_history ch
        USING (SELECT ${caseStatus.receiptNumber} AS receipt_number FROM dual) src
        ON (ch.receipt_number = src.receipt_number)
        WHEN NOT MATCHED THEN
          INSERT (receipt_number) VALUES (src.receipt_number)
      """.update.run
      
      // Insert status update
      _ <- sql"""
        INSERT INTO status_updates (receipt_number, case_type, current_status, last_updated, details)
        VALUES (${caseStatus.receiptNumber}, ${caseStatus.caseType}, ${caseStatus.currentStatus},
                ${caseStatus.lastUpdated}, ${caseStatus.details})
      """.update.run
    } yield ()

    program.transact(xa)
  }

  /** Get case by receipt number */
  override def getCaseByReceipt(receiptNumber: String): IO[Option[CaseHistory]] = {
    val query = sql"""
      SELECT s.receipt_number, s.case_type, s.current_status, s.last_updated, s.details
      FROM status_updates s
      WHERE s.receipt_number = $receiptNumber
      ORDER BY s.last_updated DESC
    """.query[(String, String, String, LocalDateTime, Option[String])]

    query.to[List].transact(xa).map { rows =>
      if (rows.isEmpty) None
      else {
        val statuses = rows.map { case (rn, ct, cs, lu, d) =>
          CaseStatus(rn, ct, cs, lu, d)
        }
        Some(CaseHistory(receiptNumber, statuses))
      }
    }
  }

  /** Get all cases */
  override def getAllCases: IO[List[CaseHistory]] = {
    // First get all receipt numbers
    val receiptsQuery = sql"""
      SELECT receipt_number FROM case_history ORDER BY receipt_number
    """.query[String].to[List]

    // Then get all status updates
    val statusesQuery = sql"""
      SELECT receipt_number, case_type, current_status, last_updated, details
      FROM status_updates
      ORDER BY receipt_number, last_updated DESC
    """.query[(String, String, String, LocalDateTime, Option[String])].to[List]

    val program = for {
      receipts <- receiptsQuery
      statuses <- statusesQuery
    } yield {
      val statusesByReceipt = statuses.groupBy(_._1)
      receipts.map { receipt =>
        val caseStatuses = statusesByReceipt.getOrElse(receipt, List.empty).map {
          case (rn, ct, cs, lu, d) => CaseStatus(rn, ct, cs, lu, d)
        }
        CaseHistory(receipt, caseStatuses)
      }
    }

    program.transact(xa)
  }

  /** Delete a case by receipt number */
  override def deleteCase(receiptNumber: String): IO[Unit] = {
    val program = for {
      deleted <- sql"""
        DELETE FROM case_history WHERE receipt_number = $receiptNumber
      """.update.run
      _ <- if (deleted == 0) {
        FC.raiseError[Unit](new NoSuchElementException(s"Receipt not found: $receiptNumber"))
      } else {
        FC.pure(())
      }
    } yield ()

    program.transact(xa) *> logger.info(s"Deleted case: $receiptNumber")
  }

  /** Export cases to a JSON file */
  override def exportToFile(filepath: String): IO[(Int, Int)] = {
    import io.circe.syntax._
    import java.nio.file.{Files, Paths}
    import java.nio.charset.StandardCharsets

    for {
      cases <- getAllCases
      json = cases.asJson.spaces2
      _ <- IO.blocking(Files.write(Paths.get(filepath), json.getBytes(StandardCharsets.UTF_8)))
      _ <- logger.info(s"Exported to: $filepath")
    } yield (cases.size, cases.map(_.statusUpdates.size).sum)
  }

  /** Import cases from a JSON file */
  override def importFromFile(filepath: String): IO[Int] = {
    import io.circe.parser._
    import scala.io.Source
    import cats.effect.Resource

    val fileResource = Resource.make(
      IO.blocking(Source.fromFile(filepath, "UTF-8"))
    )(source => IO.blocking(source.close()))

    for {
      cases <- fileResource.use { source =>
        IO.blocking {
          val content = source.mkString
          decode[List[CaseHistory]](content)
        }.flatMap {
          case Right(parsed) => IO.pure(parsed)
          case Left(error) => IO.raiseError(new RuntimeException(s"Failed to parse import file: ${error.getMessage}"))
        }
      }
      _ <- saveCases(cases)
      _ <- logger.info(s"Imported ${cases.size} case(s) from: $filepath")
    } yield cases.size
  }

  /** Check if a case exists */
  override def caseExists(receiptNumber: String): IO[Boolean] =
    sql"""
      SELECT COUNT(*) FROM case_history WHERE receipt_number = $receiptNumber
    """.query[Int].unique.transact(xa).map(_ > 0)

  /** Count all cases */
  override def countCases: IO[Int] =
    sql"SELECT COUNT(*) FROM case_history".query[Int].unique.transact(xa)

  /** Get cases with optional filter and pagination */
  override def getCases(filter: Option[String], offset: Int, limit: Int): IO[List[CaseHistory]] = {
    val baseQuery = filter match {
      case Some(f) =>
        sql"""
          SELECT receipt_number FROM case_history 
          WHERE UPPER(receipt_number) LIKE UPPER(${"%" + f + "%"})
          ORDER BY receipt_number
          OFFSET $offset ROWS FETCH NEXT $limit ROWS ONLY
        """
      case None =>
        sql"""
          SELECT receipt_number FROM case_history 
          ORDER BY receipt_number
          OFFSET $offset ROWS FETCH NEXT $limit ROWS ONLY
        """
    }

    for {
      receipts <- baseQuery.query[String].to[List].transact(xa)
      cases <- if (receipts.isEmpty) IO.pure(List.empty[CaseHistory])
               else fetchCaseHistoriesForReceipts(receipts)
    } yield cases
  }

  /** Batch fetch case histories for multiple receipts in a single query */
  private def fetchCaseHistoriesForReceipts(receipts: List[String]): IO[List[CaseHistory]] = {
    import cats.data.NonEmptyList
    NonEmptyList.fromList(receipts).fold(IO.pure(List.empty[CaseHistory])) { nel =>
      val query = fr"""
        SELECT receipt_number, case_type, current_status, last_updated, details
        FROM status_updates WHERE """ ++ Fragments.in(fr"receipt_number", nel) ++
        fr" ORDER BY receipt_number, last_updated DESC"
      
      query.query[(String, String, String, LocalDateTime, Option[String])].to[List].transact(xa).map { rows =>
        val statusesByReceipt = rows.groupBy(_._1)
        receipts.map { receipt =>
          val caseStatuses = statusesByReceipt.getOrElse(receipt, List.empty).map {
            case (rn, ct, cs, lu, d) => CaseStatus(rn, ct, cs, lu, d)
          }
          CaseHistory(receipt, caseStatuses)
        }
      }
    }
  }

  /** Count cases matching an optional filter */
  override def countCases(filter: Option[String]): IO[Int] = {
    val query = filter match {
      case Some(f) =>
        sql"""
          SELECT COUNT(*) FROM case_history 
          WHERE UPPER(receipt_number) LIKE UPPER(${"%" + f + "%"})
        """
      case None =>
        sql"SELECT COUNT(*) FROM case_history"
    }
    query.query[Int].unique.transact(xa)
  }

  /** Get cases by a list of exact receipt numbers */
  override def getCasesByReceipts(receiptNumbers: List[String]): IO[List[CaseHistory]] = {
    if (receiptNumbers.isEmpty) {
      IO.pure(List.empty)
    } else {
      // Use IN clause with NonEmptyList for safety
      import doobie.implicits._
      import cats.data.NonEmptyList

      NonEmptyList.fromList(receiptNumbers).fold(IO.pure(List.empty[CaseHistory])) { nel =>
        // First check which receipts actually exist in case_history
        val validReceiptsQuery = (fr"SELECT receipt_number FROM case_history WHERE " ++ 
          Fragments.in(fr"receipt_number", nel)).query[String].to[List]

        validReceiptsQuery.transact(xa).flatMap { validReceipts =>
          if (validReceipts.isEmpty) IO.pure(List.empty)
          else fetchCaseHistoriesForReceipts(validReceipts)
        }
      }
    }
  }
}

object OraclePersistenceManager {

  private val logger: Logger[IO] = Slf4jLogger.getLoggerFromClass[IO](this.getClass)

  /**
   * Create an Oracle persistence manager as a Resource.
   * The connection pool is automatically cleaned up when the resource is released.
   */
  def resource(config: OracleConfig): Resource[IO, OraclePersistenceManager] = {
    for {
      _ <- Resource.eval(logger.info(s"Connecting to Oracle database..."))
      _ <- Resource.eval(configureWallet(config))
      xa <- createTransactor(config)
      pm = new OraclePersistenceManager(xa)
      _ <- Resource.eval(pm.initialize)
      _ <- Resource.eval(logger.info("Oracle persistence manager ready"))
    } yield pm
  }

  /** Detect if wallet is auto-login by checking for cwallet.sso file */
  private def detectAutoLoginWallet(path: String): IO[Boolean] = {
    import java.nio.file.{Files, Paths}
    IO.blocking {
      val walletPath = Paths.get(path)
      val cwalletSso = walletPath.resolve("cwallet.sso")
      Files.exists(cwalletSso)
    }
  }

  /** Configure Oracle wallet system properties if wallet path is provided */
  private def configureWallet(config: OracleConfig): IO[Unit] = {
    config.walletPath match {
      case Some(path) =>
        logger.info(s"Oracle wallet configured at: $path") // Wallet properties set per-connection in createTransactor
      case None =>
        logger.info("No wallet configured, using standard authentication")
    }
  }

  /** Create HikariCP transactor for Oracle */
  private def createTransactor(config: OracleConfig): Resource[IO, HikariTransactor[IO]] = {
    val hikariConfig = new HikariConfig()
    hikariConfig.setDriverClassName("oracle.jdbc.OracleDriver")
    hikariConfig.setJdbcUrl(config.jdbcUrl)
    hikariConfig.setUsername(config.user)
    hikariConfig.setPassword(config.password)
    hikariConfig.setMaximumPoolSize(config.poolSize)
    hikariConfig.setMinimumIdle(2)
    hikariConfig.setConnectionTimeout(30000)
    hikariConfig.setIdleTimeout(600000)
    hikariConfig.setMaxLifetime(1800000)
    
    // Oracle-specific settings
    hikariConfig.addDataSourceProperty("oracle.jdbc.fanEnabled", "false")
    
    // Configure wallet if present
    val configureWalletIO = config.walletPath match {
      case Some(path) =>
        detectAutoLoginWallet(path).flatMap { isAutoLogin =>
          IO.delay {
            if (isAutoLogin) {
              // Auto-login wallet: skip JKS properties
              hikariConfig.addDataSourceProperty("oracle.net.tns_admin", path)
            } else {
              // Standard wallet: configure JKS properties
              hikariConfig.addDataSourceProperty("oracle.net.tns_admin", path)
              hikariConfig.addDataSourceProperty("javax.net.ssl.trustStore", s"$path/${config.trustStoreFile}")
              hikariConfig.addDataSourceProperty("javax.net.ssl.trustStoreType", "JKS")
              config.trustStorePassword.foreach { pwd =>
                hikariConfig.addDataSourceProperty("javax.net.ssl.trustStorePassword", pwd)
              }
              hikariConfig.addDataSourceProperty("javax.net.ssl.keyStore", s"$path/${config.keyStoreFile}")
              hikariConfig.addDataSourceProperty("javax.net.ssl.keyStoreType", "JKS")
              config.keyStorePassword.foreach { pwd =>
                hikariConfig.addDataSourceProperty("javax.net.ssl.keyStorePassword", pwd)
              }
            }
          }
        }
      case None => IO.unit
    }
    
    Resource.eval(configureWalletIO) *> HikariTransactor.fromHikariConfig[IO](hikariConfig)
  }

  /** Load Oracle config from Typesafe Config */
  def loadConfig: IO[OracleConfig] = IO.delay {
    import com.typesafe.config.ConfigFactory
    val config = ConfigFactory.load()
    val oracle = config.getConfig("oracle")
    
    OracleConfig(
      jdbcUrl = oracle.getString("jdbc-url"),
      user = oracle.getString("user"),
      password = oracle.getString("password"),
      walletPath = if (oracle.hasPath("wallet-path") && oracle.getString("wallet-path").nonEmpty) 
                     Some(oracle.getString("wallet-path")) 
                   else None,
      trustStorePassword = if (oracle.hasPath("trust-store-password") && oracle.getString("trust-store-password").nonEmpty)
                             Some(oracle.getString("trust-store-password"))
                           else None,
      keyStorePassword = if (oracle.hasPath("key-store-password") && oracle.getString("key-store-password").nonEmpty)
                           Some(oracle.getString("key-store-password"))
                         else None,
      trustStoreFile = if (oracle.hasPath("trust-store-file")) oracle.getString("trust-store-file") else "truststore.jks",
      keyStoreFile = if (oracle.hasPath("key-store-file")) oracle.getString("key-store-file") else "keystore.jks",
      poolSize = if (oracle.hasPath("pool-size")) oracle.getInt("pool-size") else 10
    )
  }
}
