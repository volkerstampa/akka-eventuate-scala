package sample.eventuate

import java.nio.file.{Files, Path}
import java.util.UUID

import akka.actor.{ActorSystem, ActorRef, Props}
import akka.remote.testconductor.RoleName
import akka.remote.testkit.{MultiNodeConfig, MultiNodeSpec}
import akka.pattern.ask
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import akka.testkit.TestProbe
import com.rbmhtechnology.eventuate.ReplicationEndpoint.DefaultLogName
import com.rbmhtechnology.eventuate.{Versioned, EventsourcedView, ReplicationConnection, ReplicationEndpoint}
import com.rbmhtechnology.eventuate.log.leveldb.LeveldbEventLog
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.commons.io.FileUtils
import org.scalatest.{WordSpec, Matchers}
import sample.eventuate.OrderActor._

import scala.reflect.{classTag, ClassTag}
import scala.collection.JavaConverters.mapAsJavaMapConverter

class OrderSpecMultiJvmNode1 extends OrderSpec
class OrderSpecMultiJvmNode2 extends OrderSpec

class TwoNodesReplicationConfig(config: Config) extends MultiNodeConfig {

  val nodeA = role("nodeA")
  val nodeB = role("nodeB")

  testTransport(true)
  
  def otherNode(role: RoleName): RoleName = if(role == nodeA) nodeB else nodeA

  commonConfig(config.withFallback(ConfigFactory.parseString(
    s"""
       |akka.loglevel = "ERROR"
       |akka.stdout-loglevel = "ERROR"
       |akka.test.single-expect-default = 10s
       |log.replication.transfer-batch-size-max = 3
       |log.replication.transfer-retry-interval = 1s
       |log.replication.connect-retry-interval = 1s
       |log.replication.failure-detection-limit = 60s
  """.stripMargin)))
}

class OrderSpec extends WordSpec with Matchers {

  def newOrderId: String = UUID.randomUUID().toString

  val createdBarrier = "created"

  "An OrderManager with two replicas" when {
    "an order is created on each replica" must {
      "contain both orders (on each replica)" in withTwoOrderManagers { multiNode =>
        import multiNode._

        executeCommand(CreateOrder(newOrderId))

        val emittedEvents = listener.expectMsgAllClassOf(
          List.fill(2)(classOf[OrderCreated]): _*)
        awaitCond(getState.size == 2)

        allOrders shouldBe emittedEvents.map(toOrder).toSet
      }
    }
    "an order is created on one replica and a item added on the other" must {
      "contain the order with item (on each replica)" in
        withTwoOrderManagers { multiNode =>
          import multiNode._

          runOn(config.nodeA)(executeCommand(CreateOrder(newOrderId)))
          val OrderCreated(orderId, _) = waitFor(orderCreated)

          runOn(config.nodeB)(executeCommand(AddOrderItem(orderId, "item")))
          val OrderItemAdded(_, item) = waitFor(orderItemAdded)

          allOrders shouldBe Set(Order(orderId, List(item)))
        }
    }
    "an item is added on both replica concurrently" must {
      "contain two conflicting orders (on each replica)" in
        withTwoOrderManagers { multiNode =>
          import multiNode._

          runOn(config.nodeA)(executeCommand(CreateOrder(newOrderId)))
          val OrderCreated(orderId, _) = waitFor(orderCreated)

          disconnect(config.nodeA, config.nodeB)

          executeCommand(AddOrderItem(orderId, myself.name))
          val OrderItemAdded(_, myItem) = waitFor(orderItemAdded)

          connect(config.nodeA, config.nodeB)
          val OrderItemAdded(_, otherItem) = waitFor(orderItemAdded)

          getState.get(orderId).map(allVersions) shouldBe
            Some(Set(myItem, otherItem).map(item => Order(orderId, List(item))))
        }
    }
  }

  private def allVersions(versions: Seq[Versioned[Order]]): Set[Order] =
    versions.map(_.value).toSet

  private def orderCreated(event: OrderCreated,
                           state: Map[String, Seq[Versioned[Order]]]): Boolean =
    state.get(event.orderId).isDefined

  private def orderItemAdded(event: OrderItemAdded,
                             state: Map[String, Seq[Versioned[Order]]]): Boolean =
    state.get(event.orderId).exists(_.exists(_.value.items.contains(event.item)))

  private def toOrder(event: OrderCreated) = Order(event.orderId)

  private def withTwoOrderManagers[O]
      (block: TwoOrderManagerNode => O): O = {
    withMultiReplicationNode(
      new TwoNodesReplicationConfig(_),
      new TwoOrderManagerNode(_: TwoNodesReplicationConfig))(block)
  }

  private def withMultiReplicationNode[C <: MultiNodeConfig, M <: MultiNodeSpec, O]
      (multiNodeConfigFactory: Config => C, multiNodeFactory: C => M)
      (block: M => O): O = {
    withTempDir { tempDir =>
      val config = multiNodeConfigFactory(
        ConfigFactory.parseMap(Map("log.leveldb.dir" -> tempDir.toString).asJava))
      withMultiNode(multiNodeFactory(config))(block)
    }
  }

  private def withTempDir[A](block: Path => A): A = {
    val dir = Files.createTempDirectory(getClass.getSimpleName)
    try {
      block(dir)
    } finally {
      FileUtils.deleteDirectory(dir.toFile)
    }
  }

  private def withMultiNode[M <: MultiNodeSpec, O]
      (multiNode: M)(block: M => O): O = {
    try {
      multiNode.multiNodeSpecBeforeAll()
      multiNode.enterBarrier("start-test")
      block(multiNode)
    } finally {
      multiNode.enterBarrier("test-finished")
      multiNode.multiNodeSpecAfterAll()
      // avoid that next test starts before conductor is shut down
      Thread.sleep(100 * multiNode.roles.size)
    }
  }
}

class TwoOrderManagerNode(config: TwoNodesReplicationConfig)
    extends MultiReplicationNode(config) {

  val endpoint: ReplicationEndpoint =
    newReplicationEndpointConnectedTo(config.otherNode(myself))
  val listener: TestProbe = newListener(endpoint)
  val orderManager: ActorRef = system.actorOf(Props(new OrderManager(
    replicaId[OrderManager](myself.name), endpoint.logs(DefaultLogName))))

  def executeCommand(cmd: Any): Unit = {
    (orderManager ? cmd)(testConductor.Settings.QueryTimeout).await match {
      case CommandFailure(_, cause) => throw cause
      case _ =>
    }
  }

  def waitFor[E : ClassTag](cond: (E, Map[String, Seq[Versioned[Order]]]) => Boolean): E = {
    val event = listener.expectMsgClass(classTag[E].runtimeClass).asInstanceOf[E]
    awaitCond(cond(event, getState))
    event
  }

  def getState: Map[String, Seq[Versioned[Order]]] =
    (orderManager ? GetState)(testConductor.Settings.QueryTimeout).await match {
      case GetStateSuccess(state) => state
      case GetStateFailure(cause) => throw cause
    }

  def allOrders: Set[Order] = (for {
    versionedOrders <- getState.values
    versionedOrder  <- versionedOrders
  } yield versionedOrder.value).toSet

  private def replicaId[A : ClassTag](nodeId: String) =
    s"${classTag[A].runtimeClass.getSimpleName}-$nodeId"
}

class MultiReplicationNode[C <: MultiNodeConfig](val config: C)
    extends MultiNodeSpec(config) {
  
  override def initialParticipants = roles.size
  
  def newReplicationEndpointConnectedTo(replicationPartners: RoleName*) =
    new ReplicationEndpoint(
      myself.name,
      Set(DefaultLogName),
      LeveldbEventLog.props(_),
      replicationPartners.toSet.map(toReplicationConnection))
  
  def newListener(endpoint: ReplicationEndpoint): TestProbe = 
    EventListener(endpoint.logs(DefaultLogName))

  def disconnect(from: RoleName, to: RoleName): Unit = {
    enterBarrier("before disconnect")
    runOn(from)(testConductor.blackhole(from, to, Direction.Both).await)
    enterBarrier("after disconnect")
  }

  def connect(from: RoleName, to: RoleName): Unit = {
    enterBarrier("before connect")
    runOn(from)(testConductor.passThrough(from, to, Direction.Both).await)
    enterBarrier("after connect")
  }

  private def toReplicationConnection(roleName: RoleName): ReplicationConnection = {
    val address = node(roleName).address
    ReplicationConnection(address.host.get, address.port.get, address.system)
  }
}

object EventListener {
  private class TestEventConsumer(override val eventLog: ActorRef, listener: ActorRef)
      extends EventsourcedView {

    override def onCommand = ???

    override def onEvent = {
      case event => listener ! event
    }
  }
  
  def apply(eventLog: ActorRef)(implicit system: ActorSystem): TestProbe = {
    val listener = TestProbe()
    system.actorOf(Props(new TestEventConsumer(eventLog, listener.ref)))
    listener
  }
}

