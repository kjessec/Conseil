package tech.cryptonomic.conseil.application

import tech.cryptonomic.conseil.config.{ChainEvent, LorreConfiguration}
import tech.cryptonomic.conseil.config.LorreAppConfig.CombinedConfiguration
import tech.cryptonomic.conseil.tezos.TezosTypes.{BlockData, BlockReference, BlockTagged}
import tech.cryptonomic.conseil.tezos.{
  ApiOperations,
  FeeOperations,
  TezosNodeOperator,
  TezosDatabaseOperations => TezosDb
}
import tech.cryptonomic.conseil.util.IOUtils.{lift, IOLogging}
import akka.actor.ActorSystem
import scala.concurrent.duration._
import cats.effect.{ContextShift, IO}
import cats.effect.Timer
import cats.syntax.all._
import slick.jdbc.PostgresProfile.api._
import scala.collection.SortedSet

/** Defines useful and general operations used by Lorre */
trait LorreOperations {
  self: IOLogging =>

  /** "Unpacks" lists of block-referenced elements and removes any duplicate by
    * keeping those at the highest level
    */
  protected def discardOldestDuplicates[T](taggedLists: List[BlockTagged[List[T]]]) = {
    /* Scans the association and puts them in a map adding only missing elements
     * which are asssumed to be sorted by highest block level first
     */
    def collectMostRecent(associations: List[(T, BlockReference)]): Map[T, BlockReference] =
      associations.foldLeft(Map.empty[T, BlockReference]) {
        case (collectedMap, pair) =>
          if (collectedMap.contains(pair._1)) collectedMap else collectedMap + pair
      }

    val sorted = taggedLists.flatMap {
      case BlockTagged(hash, level, timestamp, cycle, elements) =>
        elements.map(_ -> (hash, level, timestamp, cycle))
    }.sortBy {
      case (element, (hash, level, timestamp, cycle)) => level
    }(Ordering[Int].reverse)

    collectMostRecent(sorted)
  }

  /** Tries to fetch blocks head to verify if connection with Tezos node was successfully established
    * It retries consistently upon failure
    */
  protected def checkTezosConnection(
      nodeOperator: TezosNodeOperator,
      bootupConnectionCheckTimeout: FiniteDuration,
      bootupRetryInterval: FiniteDuration
  )(implicit contextShift: ContextShift[IO], timer: Timer[IO]): IO[BlockData] = {
    val tryFetchHead =
      lift(nodeOperator.getBareBlockHead())
        .timeout(bootupConnectionCheckTimeout) <* liftLog(_.info("Successfully made initial connection to Tezos"))

    tryFetchHead.handleErrorWith(
      error =>
        liftLog(_.error("Could not make initial connection to Tezos", error)) >>
            IO.sleep(bootupRetryInterval) >>
            checkTezosConnection(nodeOperator, bootupConnectionCheckTimeout, bootupRetryInterval)
    )
  }

  // Finds unprocessed levels for account refreshes (i.e. when there is a need to reload all accounts data from the chain)
  protected def unprocessedLevelsForRefreshingAccounts(db: Database, lorreConf: LorreConfiguration)(
      implicit cs: ContextShift[IO]
  ): IO[ChainEvent.AccountUpdatesEvents] =
    lorreConf.chainEvents.collectFirst {
      case ChainEvent.AccountsRefresh(levelsNeedingRefresh) if levelsNeedingRefresh.nonEmpty =>
        lift(db.run(TezosDb.fetchProcessedEventsLevels(ChainEvent.accountsRefresh.render))).map { levels =>
          //used to remove processed events
          val processed = levels.map(_.intValue).toSet
          //we want individial event levels with the associated pattern, such that we can sort them by level
          val unprocessedEvents = levelsNeedingRefresh.toList.flatMap {
            case (accountPattern, levels) => levels.filterNot(processed).sorted.map(_ -> accountPattern)
          }
          SortedSet(unprocessedEvents: _*)
        }
    }.getOrElse(IO.pure(SortedSet.empty))

  /* grabs all remainig data in the checkpoints tables and processes them */
  protected def processCheckpoints(
      accountsOps: AccountsOperations,
      delegatesOps: DelegatesOperations
  ): IO[Unit] = {
    import accountsOps.{getAccountsCheckpoint, processAccountsAndGetDelegateKeys}
    import delegatesOps.{getDelegatesCheckpoint, processDelegates}

    for {
      _ <- liftLog(_.info("Selecting all accounts left in the checkpoint table..."))
      accountsCheckpoint <- getAccountsCheckpoint
      _ <- processAccountsAndGetDelegateKeys(accountsCheckpoint, onlyProcessLatest = true)
        .unlessA(accountsCheckpoint.isEmpty)
      _ <- liftLog(_.info("Selecting all delegates left in the checkpoint table..."))
      delegatesCheckpoint <- getDelegatesCheckpoint
      _ <- processDelegates(delegatesCheckpoint, onlyProcessLatest = true)
        .unlessA(delegatesCheckpoint.isEmpty)
    } yield ()
  }

  /* Logs the tracked progress time of blocks processing.
   * Designed to be partially applied to set properties of the whole process once, and then only compute partial completion
   *
   * @param totalToProcess how many entities there were in the first place
   * @param processStartNanos a nano-time from jvm monotonic time, used to identify when the whole processing operation began
   * @param processed how many entities were processed at the current checkpoint
   * @param cs needed to shift logical threads of execution
   * @param timer needed to get the current nano-time as an effect
   */
  protected def logProcessingProgress(
      totalToProcess: Int,
      processStartNanos: Long,
      cs: ContextShift[IO],
      timer: Timer[IO]
  )(processed: Int): IO[Unit] =
    for {
      currentNanos <- timer.clock.monotonic(NANOSECONDS)
      elapsed = currentNanos - processStartNanos
      progress = processed.toDouble / totalToProcess
      eta = if (elapsed * progress > 0) Duration(scala.math.ceil(elapsed / progress) - elapsed, NANOSECONDS)
      else Duration.Inf
      _ <- cs.shift //log from another thread to keep processing other blocks
      _ <- liftLog(_.info("================================== Progress Report =================================="))
      _ <- liftLog(_.info("Completed processing {}% of total requested blocks", "%.2f".format(progress * 100)))
      _ <- liftLog(
        _.info(
          "Estimated average throughput is {}/min.",
          "%d".format(processed / Duration(elapsed, NANOSECONDS).toMinutes)
        )
      ).whenA(elapsed > 60e9)
      _ <- liftLog(
        _.info(
          "Estimated time to finish is {} hours and {} minutes.",
          eta.toHours,
          eta.toMinutes % 60
        )
      ).whenA(processed < totalToProcess && eta.isFinite && eta.toMinutes > 1)
      _ <- liftLog(_.info("====================================================================================="))
    } yield ()
}