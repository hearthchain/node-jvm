package tech.hearth.generator

import cats.implicits.showInterpolator
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.Logger
import tech.hearth.Application
import tech.hearth.account.AddressScheme
import tech.hearth.generator.GeneratorSettings.NodeAddress
import tech.hearth.generator.Preconditions.{PGenSettings, UniverseHolder}
import tech.hearth.generator.cli.ScoptImplicits
import tech.hearth.generator.utils.Universe
import tech.hearth.network.client.NetworkSender
import tech.hearth.transaction.Transaction
import tech.hearth.utils.NTP
import monix.execution.Scheduler
import org.asynchttpclient.AsyncHttpClient
import org.asynchttpclient.Dsl.asyncHttpClient
import org.slf4j.LoggerFactory
import pureconfig.*
import scopt.OptionParser

import java.io.File
import java.util.concurrent.Executors
import scala.concurrent.*
import scala.concurrent.duration.*
import scala.util.{Failure, Random, Success}

object TransactionsGeneratorApp extends ScoptImplicits {

  def main(args: Array[String]): Unit = {
    implicit val httpClient: AsyncHttpClient = asyncHttpClient()
    val log                                  = Logger(LoggerFactory.getLogger("generator"))

    val parser = new OptionParser[GeneratorSettings]("generator") {
      head("TransactionsGenerator - Waves load testing transactions generator")
      opt[File]('c', "configuration").valueName("<file>").text("generator configuration path")
      opt[FiniteDuration]('d', "delay").valueName("<delay>").text("delay between iterations").action { (v, c) =>
        c.copy(worker = c.worker.copy(delay = v))
      }
      opt[Boolean]('r', "auto-reconnect").valueName("<true|false>").text("reconnect on errors").action { (v, c) =>
        c.copy(worker = c.worker.copy(autoReconnect = v))
      }
      help("help").text("display this help message")

      cmd("narrow")
        .action { (_, c) =>
          c.copy(mode = Mode.NARROW)
        }
        .text("Run transactions between pre-defined accounts")
        .children(
          opt[Int]("transactions").abbr("t").optional().text("number of transactions").action { (x, c) =>
            c.copy(narrow = c.narrow.copy(transactions = x))
          }
        )

      cmd("wide")
        .action { (_, c) =>
          c.copy(mode = Mode.WIDE)
        }
        .text("Run transactions those transfer funds to another accounts")
        .children(
          opt[Int]("transactions").abbr("t").optional().text("number of transactions").action { (x, c) =>
            c.copy(wide = c.wide.copy(transactions = x))
          },
          opt[Option[Int]]("limit-accounts").abbr("la").optional().text("limit recipients").action { (x, c) =>
            c.copy(wide = c.wide.copy(limitDestAccounts = x))
          }
        )

      cmd("dyn-wide")
        .action { (_, c) =>
          c.copy(mode = Mode.DYN_WIDE)
        }
        .text("Like wide, but the number of transactions is changed during the iteration")
        .children(
          opt[Int]("start").abbr("s").optional().text("initial amount of transactions").action { (x, c) =>
            c.copy(dynWide = c.dynWide.copy(start = x))
          },
          opt[Double]("grow-adder").abbr("g").optional().action { (x, c) =>
            c.copy(dynWide = c.dynWide.copy(growAdder = x))
          },
          opt[Int]("max").abbr("m").optional().action { (x, c) =>
            c.copy(dynWide = c.dynWide.copy(maxTxsPerRequest = Some(x)))
          },
          opt[Option[Int]]("limit-accounts").abbr("la").optional().text("limit recipients").action { (x, c) =>
            c.copy(dynWide = c.dynWide.copy(limitDestAccounts = x))
          }
        )

    }

    val configParamParser = new OptionParser[File]("configuration") {
      opt[String]('c', "configuration").action { case (c, _) => new File(c) }

      override def errorOnUnknownArgument: Boolean = false

      override def reportWarning(msg: String): Unit = ()
    }

    val externalConf =
      configParamParser
        .parse(args, new File("generator.local.conf"))
        .getOrElse(throw new RuntimeException("Failed to parse configuration path from command line parameters"))

    val wavesSettings = Application.loadApplicationConfig(if (externalConf.isFile) Some(externalConf) else None)

    val defaultConfig =
      ConfigSource.fromConfig(wavesSettings.config).at("waves.generator").loadOrThrow[GeneratorSettings]

    parser.parse(args, defaultConfig) match {
      case None => parser.failure("Failed to parse command line parameters")
      case Some(finalConfig) =>
        log.info(show"The final configuration: \n$finalConfig")

        AddressScheme.current = new AddressScheme {
          override val chainId: Byte = finalConfig.addressScheme.toByte
        }

        val time = new NTP("pool.ntp.org")

        val preconditions =
          ConfigSource.fromConfig(ConfigFactory.load("preconditions.conf")).at("preconditions").loadOrThrow[Option[PGenSettings]]

        val (universe, initialUniTransactions, initialTailTransactions) = preconditions
          .fold((UniverseHolder(), List.empty[Transaction], List.empty[Transaction]))(
            Preconditions.mk(_, finalConfig.privateKeyAccounts, time)
          )

        Universe.Leases = universe.leases

        val generator: TransactionGenerator = finalConfig.mode match {
          case Mode.NARROW   => NarrowTransactionGenerator(finalConfig.narrow, finalConfig.privateKeyAccounts)
          case Mode.WIDE     => new WideTransactionGenerator(finalConfig.wide, finalConfig.privateKeyAccounts)
          case Mode.DYN_WIDE => new DynamicWideTransactionGenerator(finalConfig.dynWide, finalConfig.privateKeyAccounts)
        }

        val threadPool                            = Executors.newFixedThreadPool(Math.max(1, finalConfig.sendTo.size))
        implicit val ec: ExecutionContextExecutor = ExecutionContext.fromExecutor(threadPool)

        val sender = new NetworkSender(wavesSettings.networkSettings.trafficLogger, finalConfig.addressScheme, "generator", nonce = Random.nextLong())

        sys.addShutdownHook(sender.close())

        @volatile
        var canContinue = true

        sys.addShutdownHook {
          log.error("Stopping generator")
          canContinue = false
        }

        if (finalConfig.worker.workingTime > Duration.Zero) {
          log.info(s"Generator will be stopped after ${finalConfig.worker.workingTime}")

          Scheduler.global.scheduleOnce(finalConfig.worker.workingTime) {
            log.warn(s"Stopping generator after: ${finalConfig.worker.workingTime}")
            canContinue = false
          }
        }

        val initialGenTransactions     = generator.initial
        val initialGenTailTransactions = generator.tailInitial

        log.info(s"Universe precondition transactions size: ${initialUniTransactions.size}")
        log.info(s"Generator precondition transactions size: ${initialGenTransactions.size}")
        log.info(s"Universe precondition tail transactions size: ${initialTailTransactions.size}")
        log.info(s"Generator precondition tail transactions size: ${initialGenTailTransactions.size}")

        val workers = finalConfig.sendTo.map { case NodeAddress(node, nodeRestUrl) =>
          log.info(s"Creating worker: ${node.getHostString}:${node.getPort}")
          // new Worker(finalConfig.worker, sender, node, generator, initialTransactions.map(RawBytes.from))
          new Worker(
            finalConfig.worker,
            Iterator.continually(generator.next()).flatten,
            sender,
            node,
            nodeRestUrl,
            () => canContinue,
            initialUniTransactions ++ initialGenTransactions,
            finalConfig.privateKeyAccounts.map(_.toAddress.toString),
            initialTailTransactions ++ initialGenTailTransactions
          )
        }

        def close(status: Int): Unit = {
          sender.close()
          time.close()
          threadPool.shutdown()
          System.exit(status)
        }

        Future
          .sequence(workers.map(_.run()))
          .onComplete {
            case Success(_) =>
              log.info("Done")
              close(0)

            case Failure(e) =>
              log.error("Failed", e)
              close(1)
          }
    }
  }
}
