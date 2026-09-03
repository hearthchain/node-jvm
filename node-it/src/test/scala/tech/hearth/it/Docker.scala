package tech.hearth.it

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.javaprop.JavaPropsMapper
import com.google.common.primitives.Ints.*
import com.spotify.docker.client.messages.*
import com.spotify.docker.client.messages.EndpointConfig.EndpointIpamConfig
import com.spotify.docker.client.{DefaultDockerClient, DockerClient}
import com.typesafe.config.ConfigFactory.*
import com.typesafe.config.{Config, ConfigFactory, ConfigRenderOptions}
import tech.hearth.block.Block
import tech.hearth.common.utils.EitherExt2.*
import tech.hearth.it.api.AsyncHttpApi.*
import tech.hearth.it.util.GlobalTimer.instance as timer
import tech.hearth.settings.*
import tech.hearth.state.GenesisBlockHeight
import tech.hearth.utils.ScorexLogging
import monix.eval.Coeval
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.io.IOUtils
import org.asynchttpclient.Dsl.*
import pureconfig.ConfigSource

import java.io.{FileOutputStream, IOException}
import java.net.{InetAddress, InetSocketAddress, URI, URL}
import java.nio.file.{Files, Path, Paths}
import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, Duration as JDuration}
import java.util.Collections.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.{Properties, List as JList, Map as JMap}
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scala.concurrent.{Await, Future, blocking}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import scala.util.{Random, Try}

class Docker(
    suiteConfig: Config = empty,
    tag: String = "",
    enableProfiling: Boolean = false,
    enableDebugger: Boolean = false,
    // Nodes peer over the container network, so the node-to-node port is only worth publishing for a suite that
    // speaks the binary protocol from the test JVM itself (see networkAddressAccessibleFromHost).
    publishNetworkPort: Boolean = false,
    imageName: String = Docker.NodeImageName
) extends AutoCloseable
    with ScorexLogging {

  import Docker.*

  private val http = asyncHttpClient(
    config()
      .setNettyTimer(timer)
      .setMaxConnections(18)
      .setMaxConnectionsPerHost(3)
      .setMaxRequestRetry(1)
      .setReadTimeout(JDuration.ofSeconds(10))
      .setKeepAlive(false)
      .setRequestTimeout(JDuration.ofSeconds(10))
  )

  private val client = DefaultDockerClient.fromEnv().build()

  private val nodes     = ConcurrentHashMap.newKeySet[DockerNode]()
  private val isStopped = new AtomicBoolean(false)

  dumpContainers(client.listContainers())
  sys.addShutdownHook {
    log.debug("Shutdown hook")
    close()
  }

  // a random network in 10.x.x.x range
  val networkSeed = Random.nextInt(0x100000) << 4 | 0x0a000000
  // 10.x.x.x/28 network will accommodate up to 13 nodes
  private val networkPrefix = s"${InetAddress.getByAddress(toByteArray(networkSeed)).getHostAddress}/28"

  private val logDir: Coeval[Path] = Coeval.evalOnce {
    val r = Option(System.getProperty("hearth.it.logging.dir"))
      .map(Paths.get(_))
      .getOrElse(Paths.get(System.getProperty("user.dir"), "logs", RunId, tag.replaceAll("""(\w)\w*\.""", "$1.")))

    Files.createDirectories(r)
    r
  }

  private val genesisOverride = Docker.genesisOverride()

  private def ipForNode(nodeId: Int) = InetAddress.getByAddress(toByteArray(nodeId & 0xf | networkSeed)).getHostAddress

  private lazy val hearthNetwork: Network = {
    val id          = Random.nextInt(Int.MaxValue)
    val networkName = s"hearth-$id"

    def network: Option[Network] =
      try {
        val networks = client.listNetworks(DockerClient.ListNetworksParam.byNetworkName(networkName))
        if (networks.isEmpty) None else Some(networks.get(0))
      } catch {
        case NonFatal(_) => network
      }

    def attempt(rest: Int): Network =
      try {
        network match {
          case Some(n) =>
            val ipam = n
              .ipam()
              .config()
              .asScala
              .map(n => s"subnet=${n.subnet()}, ip range=${n.ipRange()}")
              .mkString(", ")
            log.info(s"Network ${n.name()} (id: ${n.id()}) is created for $tag, ipam: $ipam")
            n
          case None =>
            log.debug(s"Creating network $networkName for $tag")
            // Specify the network manually because of race conditions: https://github.com/moby/moby/issues/20648
            val r = client.createNetwork(
              NetworkConfig
                .builder()
                .name(networkName)
                .ipam(
                  Ipam
                    .builder()
                    .driver("default")
                    .config(singletonList(IpamConfig.create(networkPrefix, networkPrefix, ipForNode(0xe))))
                    .build()
                )
                .checkDuplicate(true)
                .build()
            )
            Option(r.warnings()).foreach(log.warn(_))
            attempt(rest - 1)
        }
      } catch {
        case NonFatal(e) =>
          log.warn(s"Can not create a network for $tag", e)
          if (rest == 0) throw e else attempt(rest - 1)
      }

    attempt(5)
  }

  def createNetwork: Network = hearthNetwork

  def startNodes(nodeConfigs: Seq[Config]): Seq[DockerNode] = {
    log.trace(s"Starting ${nodeConfigs.size} containers")
    val all = nodeConfigs.map(startNodeInternal(_))
    Await.result(
      Future.traverse(all)(_.waitForStartup()),
      5.minutes
    )
    all
  }

  def startNode(nodeConfig: Config, autoConnect: Boolean = true): DockerNode = {
    val node = startNodeInternal(nodeConfig, autoConnect)
    Await.result(node.waitForStartup(), 3.minutes)
    node
  }

  private def peersFor(nodeName: String): Seq[InetSocketAddress] = {
    nodes.asScala
      .filterNot(_.name == nodeName)
      .filterNot { node =>
        // Exclude disconnected
        client.inspectContainer(node.containerId).networkSettings().networks().isEmpty
      }
      .map(_.networkAddress)
      .toSeq
  }

  private def connectToAll(node: DockerNode): Future[Unit] = {
    def connectToOne(address: InetSocketAddress): Future[Unit] = {
      for {
        _              <- node.connect(address)
        _              <- Future(blocking(Thread.sleep(1.seconds.toMillis)))
        connectedPeers <- node.connectedPeers
        _ <- {
          val connectedAddresses = connectedPeers.map(_.address.replaceAll("""^.*/([\d\.]+).+$""", "$1")).sorted
          log.debug(s"Looking for ${address.getHostName} in $connectedAddresses")
          if (connectedAddresses.contains(address.getHostName)) Future.successful(())
          else {
            log.debug(s"Not found ${address.getHostName}, retrying")
            connectToOne(address)
          }
        }
      } yield ()
    }

    val seedAddresses = peersFor(node.name)
    if (seedAddresses.isEmpty)
      Future.successful(())
    else
      Future
        .traverse(seedAddresses)(connectToOne)
        .map(_ => ())
  }

  private def startNodeInternal(nodeConfig: Config, autoConnect: Boolean = true): DockerNode =
    try {
      val nodeName = nodeConfig.getString("hearth.network.node-name")
      val peersOverrides = if (autoConnect) {
        val otherAddrs = peersFor(nodeName)

        ConfigFactory
          .parseMap(Map("known-peers" -> otherAddrs.map(addr => s"${addr.getHostString}:${addr.getPort}").asJava).asJava)
          .atPath("hearth.network")
      } else ConfigFactory.empty()

      val overrides = peersOverrides
        .withFallback(nodeConfig)
        .withFallback(suiteConfig)
        .withFallback(genesisOverride)
        .withFallback(configTemplate)

      val actualConfig = overrides
        .withFallback(defaultApplication())
        .withFallback(defaultReference())
        .resolve()

      val networkPort          = actualConfig.getString("hearth.network.port")
      val internalDebuggerPort = 5005

      val nodeNumber = nodeName.replace("node", "").toInt
      val ip         = ipForNode(nodeNumber)

      val javaOptions = Option(System.getenv("CONTAINER_JAVA_OPTS")).getOrElse("")
      val configOverrides: String = {
        val ntpServer    = Option(System.getenv("NTP_SERVER")).fold("")(x => s"-Dhearth.ntp-server=$x ")
        val maxCacheSize = Option(System.getenv("MAX_CACHE_SIZE")).fold("")(x => s"-Dhearth.max-cache-size=$x ")

        var config = s"$javaOptions ${renderProperties(asProperties(overrides))} " +
          s"-Dlogback.stdout.level=TRACE -Dlogback.file.level=OFF -Dhearth.network.declared-address=$ip:$networkPort $ntpServer $maxCacheSize"

        // Debugger
        if (enableDebugger) config += s"-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:$internalDebuggerPort "

        config
      }

      val profilerConfigEnv = if (enableProfiling) {
        // https://www.yourkit.com/docs/java/help/startup_options.jsp
        s"YOURKIT_OPTS=port=$ProfilerPort,listen=all,sampling,monitors,sessionname=HearthNode,dir=$ContainerRoot/profiler,logdir=$ContainerRoot,onexit=snapshot"
      } else ""

      val debuggerPort = if (enableDebugger) Docker.freeDebuggerPort() else 0

      // Every published port costs a host bind out of the ephemeral range that dockerd shares with the test JVM's own
      // outbound connections; publishing all of them for every node exhausted it under CI's parallelism and failed
      // container starts with "address already in use". Publish only what this node is actually reached by from the
      // host - the extensions a suite enables say which servers are even running inside it.
      val extensions = actualConfig.getStringList("hearth.extensions").asScala.toSeq
      val publishedPorts = Seq(
        Some(actualConfig.getInt("hearth.rest-api.port")),
        Option.when(extensions.contains(GrpcExtension))(actualConfig.getInt("hearth.grpc.port")),
        Option.when(extensions.contains(BlockchainUpdatesExtension))(actualConfig.getInt("hearth.blockchain-updates.grpc-port")),
        Option.when(publishNetworkPort)(networkPort.toInt),
        Option.when(enableDebugger)(internalDebuggerPort)
      ).flatten

      val hostConfig = HostConfig
        .builder()
        .portBindings(
          publishedPorts
            .map { port =>
              // An empty host ip keeps the dual-stack binding publishAllPorts used to give these ports, so `localhost`
              // reaches them whichever way it resolves.
              val binding = if (port == internalDebuggerPort) PortBinding.of("0.0.0.0", debuggerPort) else PortBinding.randomPort("")
              s"$port" -> Seq(binding).asJava
            }
            .toMap
            .asJava
        )
        .build()

      val envs = Seq(
        s"JAVA_OPTS=$configOverrides",
        profilerConfigEnv
      ).filter(_.nonEmpty)

      val containerConfig = ContainerConfig
        .builder()
        .image(imageName)
        .exposedPorts(publishedPorts.map(_.toString)*)
        .networkingConfig(ContainerConfig.NetworkingConfig.create(Map(hearthNetwork.name() -> endpointConfigFor(nodeName)).asJava))
        .hostConfig(hostConfig)
        .env(envs*)
        .build()

      val containerId = {
        val jenkinsJobIdFromEnv = sys.env.get("JENKINS_JOB_ID").fold("")(s => s"-$s")
        val containerName       = s"${hearthNetwork.name()}-$nodeName$jenkinsJobIdFromEnv"
        dumpContainers(
          client.listContainers(DockerClient.ListContainersParam.filter("name", containerName)),
          "Containers with same name"
        )

        log.debug(s"Creating container $containerName at $ip with options: $javaOptions")
        val r = client.createContainer(containerConfig, containerName)
        Option(r.warnings().asScala).toSeq.flatten.foreach(log.warn(_))
        r.id()
      }

      client.startContainer(containerId)

      val node = new DockerNode(actualConfig, containerId, getNodeInfo(containerId, HearthSettings.fromRootConfig(actualConfig)))
      nodes.add(node)
      log.debug(s"Started $containerId -> ${node.name}: ${node.nodeInfo}${if (enableDebugger) s", debugger port = $debuggerPort" else ""}")
      node
    } catch {
      case NonFatal(e) =>
        log.error("Can't start a container", e)
        dumpContainers(client.listContainers())
        throw e
    }

  private def getNodeInfo(containerId: String, settings: HearthSettings): NodeInfo = {
    val restApiPort = settings.restAPISettings.port
    // assume test nodes always have an open port
    val networkPort = settings.networkSettings.derivedBindAddress.get.getPort

    val containerInfo   = inspectContainer(containerId)
    val hearthIpAddress = containerInfo.networkSettings().networks().get(hearthNetwork.name()).ipAddress()

    NodeInfo(restApiPort, networkPort, hearthIpAddress, containerInfo.networkSettings().ports())
  }

  @tailrec
  private def inspectContainer(containerId: String): ContainerInfo = {
    val containerInfo = client.inspectContainer(containerId)
    if (containerInfo.networkSettings().networks().asScala.contains(hearthNetwork.name())) containerInfo
    else {
      log.debug(s"Container $containerId has not connected to the network ${hearthNetwork.name()} yet, retry")
      Thread.sleep(1000)
      inspectContainer(containerId)
    }
  }

  def stopContainer(node: DockerNode): String = {
    val id = node.containerId
    log.info(s"Stopping container with id: $id")
    client.stopContainer(node.containerId, 10)
    saveProfile(node)
    saveLog(node)
    val containerInfo = client.inspectContainer(node.containerId)
    log.debug(s"""Container information for ${node.name}:
                 |Exit code: ${containerInfo.state().exitCode()}
                 |Error: ${containerInfo.state().error()}
                 |Status: ${containerInfo.state().status()}
                 |OOM killed: ${containerInfo.state().oomKilled()}""".stripMargin)
    id
  }

  def printThreadDump(node: DockerNode): Unit = {
    val id = node.containerId
    log.info(s"Saving thread dump for: $id")
    client.killContainer(id, DockerClient.Signal.SIGQUIT)
  }

  def startContainer(id: String): Unit = {
    client.startContainer(id)
    nodes.asScala.find(_.containerId == id).foreach { node =>
      node.nodeInfo = getNodeInfo(node.containerId, node.settings)
      Await.result(node.waitForStartup(), 3.minutes)
    }
  }

  def killAndStartContainer(node: DockerNode): DockerNode = {
    val id = node.containerId
    log.info(s"Killing container with id: $id")
    client.killContainer(id, DockerClient.Signal.SIGINT)
    saveProfile(node)
    saveLog(node)
    client.startContainer(id)
    node.nodeInfo = getNodeInfo(node.containerId, node.settings)
    Await.result(
      node.waitForStartup().flatMap(_ => connectToAll(node)),
      3.minutes
    )
    node
  }

  def restartNode(node: DockerNode, configUpdates: Config = empty): DockerNode = {
    Await.result(node.waitForHeightArise, 3.minutes)

    if (configUpdates != empty) {
      val renderedConfig = renderProperties(asProperties(configUpdates))

      // Docker do not allow updating ENV https://github.com/moby/moby/issues/8838 :(
      log.debug("Set new config directly in the entrypoint.sh script")
      val shPath = "/usr/share/hearth/bin/entrypoint.sh"
      val scriptCmd: Array[String] =
        Array("sh", "-c", s"sed -i 's|$${JAVA_OPTS}|$${JAVA_OPTS} $renderedConfig|' $shPath && cat $shPath")

      val execScriptCmd = client.execCreate(node.containerId, scriptCmd).id()
      client.execStart(execScriptCmd)
    }

    restartContainer(node)
  }

  override def close(): Unit = {
    if (isStopped.compareAndSet(false, true)) {
      log.info("Stopping containers")

      nodes.asScala.foreach { node =>
        try {
          client.stopContainer(node.containerId, if (enableProfiling) 60 else 0)
          log.debug(s"Container ${node.name} stopped with exit status: ${client.waitContainer(node.containerId).statusCode()}")
        } catch {
          case NonFatal(e) =>
            log.warn(s"Can't stop the container of ${node.name}", e)
        }

        try {
          saveLog(node)
          saveProfile(node)

          val containerInfo = client.inspectContainer(node.containerId)
          log.debug(s"""Container information for ${node.name}:
                       |Exit code: ${containerInfo.state().exitCode()}
                       |Error: ${containerInfo.state().error()}
                       |Status: ${containerInfo.state().status()}
                       |OOM killed: ${containerInfo.state().oomKilled()}""".stripMargin)
        } catch {
          case NonFatal(e) => log.warn(s"Can't save node logs: ${node.name}", e)
        }

        try {
          client.removeContainer(node.containerId)
        } catch {
          case NonFatal(e) => log.warn(s"Can't remove the container of ${node.name}", e)
        }
      }

      try {
        client.removeNetwork(hearthNetwork.id)
      } catch {
        case NonFatal(e) =>
          // https://github.com/moby/moby/issues/17217
          log.warn(s"Can not remove network ${hearthNetwork.name()}", e)
      }

      http.close()
      client.close()
    }
  }

  private def saveLog(node: DockerNode): Unit = {
    val containerId = node.containerId
    val logFile     = logDir().resolve(s"${node.name}.log").toFile
    log.info(s"Writing logs of $containerId to ${logFile.getAbsolutePath}")

    val fileStream = new FileOutputStream(logFile, false)
    try {
      client
        .logs(
          containerId,
          DockerClient.LogsParam.follow(),
          DockerClient.LogsParam.stdout(),
          DockerClient.LogsParam.stderr()
        )
        .attach(fileStream, fileStream)
    } finally {
      fileStream.close()
    }
  }

  private def saveProfile(node: DockerNode): Unit = if (enableProfiling) {
    try {
      val profilerDirStream = client.archiveContainer(node.containerId, ContainerRoot.resolve("profiler").toString)

      try {
        val archiveStream = new ArchiveStreamFactory().createArchiveInputStream(ArchiveStreamFactory.TAR, profilerDirStream)
        val snapshotFile = Iterator
          .continually(Option(archiveStream.getNextEntry))
          .takeWhile(_.nonEmpty)
          .collectFirst {
            case Some(entry: TarArchiveEntry) if entry.isFile && entry.getName.contains(".snapshot") => entry
          }

        snapshotFile.foreach { archiveFile =>
          val output = new FileOutputStream(logDir().resolve(s"${node.name}.snapshot").toFile)
          try {
            IOUtils.copy(archiveStream, output)
            log.info(s"The snapshot of ${node.name} was successfully saved")
          } catch {
            case e: Throwable => throw new IOException(s"Can't copy ${archiveFile.getName} of ${node.name} to local fs", e)
          } finally {
            output.close()
          }
        }
      } catch {
        case e: Throwable => throw new IOException(s"Can't read a profiler directory stream of ${node.name}", e)
      } finally {
        // Some kind of https://github.com/spotify/docker-client/issues/745
        // But we have to close this stream, otherwise the thread will be blocked
        Try(profilerDirStream.close())
      }
    } catch {
      case e: Throwable => log.warn(s"Can't save profiler logs of ${node.name}", e)
    }
  }

  def disconnectFromNetwork(node: DockerNode): Unit = disconnectFromNetwork(node.containerId)

  private def disconnectFromNetwork(containerId: String): Unit = {
    log.info(s"Trying to disconnect container $containerId from network ...")
    client.disconnectFromNetwork(containerId, hearthNetwork.id())
  }

  def restartContainer(node: DockerNode): DockerNode = {
    val id            = node.containerId
    val containerInfo = inspectContainer(id)
    val ports         = containerInfo.networkSettings().ports()
    log.info(s"New ports: ${ports.toString}")
    client.restartContainer(id, 10)

    node.nodeInfo = Iterator
      .continually {
        Thread.sleep(1.second.toMillis)
        getNodeInfo(node.containerId, node.settings)
      }
      .dropWhile(_.ports.isEmpty)
      .next()

    node.nodeInfo = getNodeInfo(node.containerId, node.settings)
    Await.result(
      node.waitForStartup().flatMap(_ => connectToAll(node)),
      3.minutes
    )
    node
  }

  def connectToNetwork(nodes: Seq[DockerNode]): Unit = {
    nodes.foreach(connectToNetwork)
    Await.result(Future.traverse(nodes)(connectToAll), 1.minute)
  }

  private def connectToNetwork(node: DockerNode): Unit = {
    log.info(s"Trying to connect node $node to network ...")
    client.connectToNetwork(
      hearthNetwork.id(),
      NetworkConnection
        .builder()
        .containerId(node.containerId)
        .endpointConfig(endpointConfigFor(node.name))
        .build()
    )

    node.nodeInfo = getNodeInfo(node.containerId, node.settings)
    log.debug(s"New ${node.name} settings: ${node.nodeInfo}")
  }

  private def endpointConfigFor(nodeName: String): EndpointConfig = {
    val nodeNumber = nodeName.replace("node", "").toInt
    val ip         = ipForNode(nodeNumber)

    EndpointConfig
      .builder()
      .ipAddress(ip)
      .ipamConfig(EndpointIpamConfig.builder().ipv4Address(ip).build())
      .build()
  }

  private def dumpContainers(containers: java.util.List[Container], label: String = "Containers"): Unit = {
    val x =
      if (containers.isEmpty) "No"
      else
        "\n" + containers.asScala
          .map { x =>
            s"Container(${x.id()}, status: ${x.status()}, names: ${x.names().asScala.mkString(", ")})"
          }
          .mkString("\n")

    log.debug(s"$label: $x")
  }

}

object Docker {
  val NodeImageName: String = "hearth/node-it:latest"

  val GrpcExtension              = "tech.hearth.api.grpc.GRPCServerExtension"
  val BlockchainUpdatesExtension = "tech.hearth.events.BlockchainUpdates"

  private val ContainerRoot = Paths.get("/usr/share/hearth")
  private val ProfilerPort  = 10001

  private val RunId = Option(System.getenv("RUN_ID")).getOrElse(DateTimeFormatter.ofPattern("MM-dd--HH_mm_ss").format(LocalDateTime.now()))

  private val jsonMapper  = new ObjectMapper
  private val propsMapper = new JavaPropsMapper

  val configTemplate: Config = parseResources("template.conf")
  val initialHearthAmount: Long =
    ConfigSource
      .fromConfig(configTemplate)
      .at("hearth.blockchain.custom.predefined-snapshots")
      .loadOrThrow[Seq[PredefinedSnapshotSettings]]
      .find(_.height == GenesisBlockHeight.toInt)
      .fold(Seq.empty[GenesisBalanceSettings])(_.balances)
      .map(_.hearth)
      .sum

  def genesisOverride(): Config = {
    // Starting a node and applying the genesis block takes a non-negligible amount of time. If we do not introduce an offset,
    // the system will treat the genesis block as if it was created in the past. In CI runs, this time gap can reach up
    // to 30 seconds.
    //
    // Block mining starts immediately after genesis is applied. As a result, there may be less time available for a
    // second block than some tests require (for example, to populate it with transactions).
    //
    // If the genesis block timestamp is slightly in the future, it will still be accepted. The only side effect is a
    // delayed start of mining.
    //
    // The chosen offset represents a compromise between realistic timing and test stability.
    val offsetMs        = 12_000
    val genesisTs: Long = System.currentTimeMillis() + offsetMs

    // state-hash/block-id are also unpinned here so Block.genesis computes them fresh below, instead of validating
    // them against custom-defaults.conf's placeholder values (which this custom network doesn't match).
    val timestampOverrides = parseString(s"""hearth.blockchain.custom.genesis {
                                            |  timestamp = $genesisTs
                                            |  block-timestamp = $genesisTs
                                            |  signature = null # To calculate it in Block.genesis
                                            |  state-hash = null
                                            |  block-id = null
                                            |}""".stripMargin)

    val genesisConfig = timestampOverrides.withFallback(configTemplate)
    val bs            = ConfigSource.fromConfig(genesisConfig).at("hearth.blockchain").loadOrThrow[BlockchainSettings]
    // The final config sent to a container is flattened into -D system properties (see startNodeInternal), which
    // cannot represent an absent/null value, so all three commitments are pinned here to the concrete values this
    // genesis block actually computes, rather than left null and risking custom-defaults.conf's placeholders (or an
    // empty string from the properties round-trip) leaking through as a mismatched commitment.
    val genesisBlock = Block.genesis(bs).explicitGet()

    parseString(s"""hearth.blockchain.custom.genesis {
                   |  signature = ${genesisBlock.signature}
                   |  state-hash = ${genesisBlock.header.stateHash.get}
                   |  block-id = ${genesisBlock.id()}
                   |}""".stripMargin).withFallback(timestampOverrides)
  }

  // A container's node pins this itself from its own config (Application.startNode); this JVM runs the
  // test/genesis-computation code outside any container, so it has to pin the same network by hand.
  tech.hearth.crypto.Address.setDefaultHrp(
    ConfigSource.fromConfig(configTemplate).at("hearth.blockchain.custom.network-id").loadOrThrow[String]
  )

  def apply(owner: Class[?]): Docker = new Docker(tag = owner.getSimpleName)

  private def asProperties(config: Config): Properties = {
    val jsonConfig = config.resolve().root().render(ConfigRenderOptions.concise())
    propsMapper.writeValueAsProperties(jsonMapper.readTree(jsonConfig))
  }

  private def renderProperties(p: Properties) =
    p.asScala
      .map {
        case (k, v) if v.contains(" ") => k -> s""""$v""""
        case x                         => x
      }
      .map { case (k, v) => s"-D$k=$v" }
      .mkString(" ")

  case class NodeInfo(restApiPort: Int, networkPort: Int, hearthIpAddress: String, ports: JMap[String, JList[PortBinding]]) {
    val nodeApiEndpoint: URL = URI.create(s"http://localhost:${externalPort(restApiPort)}").toURL
    // Lazy: only suites that ask for the node-to-node port have it published (see Docker's publishNetworkPort).
    lazy val hostNetworkAddress: InetSocketAddress = new InetSocketAddress("localhost", externalPort(networkPort))
    val containerNetworkAddress: InetSocketAddress = new InetSocketAddress(hearthIpAddress, networkPort)

    // An exposed-but-unpublished port is still a key here, with an empty binding list rather than a missing entry, so
    // emptiness is what "not published" actually looks like.
    def externalPort(internalPort: Int): Int = {
      val bindings = ports.get(s"$internalPort/tcp")
      require(
        bindings != null && !bindings.isEmpty,
        s"Port $internalPort is not published, published: ${ports.asScala.collect { case (p, b) if !b.isEmpty => p }.mkString(", ")}"
      )
      bindings.get(0).hostPort().toInt
    }
  }

  class DockerNode(config: Config, val containerId: String, private[Docker] var nodeInfo: NodeInfo) extends Node(config) {
    override def nodeExternalPort(internalPort: Int): Int = nodeInfo.externalPort(internalPort)

    override def nodeApiEndpoint: URL = nodeInfo.nodeApiEndpoint

    override val apiKey = "integration-test-rest-api"

    override def networkAddress: InetSocketAddress = nodeInfo.containerNetworkAddress

    def getConfig: Config = config

    override def networkAddressAccessibleFromHost: InetSocketAddress = nodeInfo.hostNetworkAddress
  }

  private val debuggerPort            = new AtomicInteger(11000)
  private def freeDebuggerPort(): Int = debuggerPort.getAndIncrement()
}
