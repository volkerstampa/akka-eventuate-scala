package sample.eventuate

import java.util.UUID

import akka.util.Timeout
import akka.pattern.ask
import OrderActor._
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.Random

object OrderBot extends App {

  val locations = List(
    new OrderLocation("location-C"),
    new OrderLocation("location-D"))
  val actions = List(
    CreateOrderAction,      CreateOrderAction,
    AddOrderItemAction,     AddOrderItemAction,
    CreateOrderAction,      AddOrderItemAction,
    RemoveOrderItemAction,  CancelOrderAction)

  for(i <- 0 to Action.total) {
    val currentLocation = locations(i % locations.size)
    val action = actions(i % actions.size)
    action.execute(currentLocation, i)
    Thread.sleep(3000)
  }
  locations.foreach(loc => Await.result(loc.system.terminate(), 5.seconds))
}

trait Action {
  val log = LoggerFactory.getLogger(getClass)
  val askTimeoutDuration = 3.seconds
  implicit val askTimeout = Timeout(askTimeoutDuration)
  def execute(location: OrderLocation, i: Int): Unit

  def newOrderId: String = UUID.randomUUID().toString

  def pickOrder(location: OrderLocation, i: Int): Option[Order] =
    Await.result(location.manager ? GetState, askTimeoutDuration) match {
      case GetStateSuccess(state) => for {
        order <- state.values.toSeq.drop((i - 1) % state.size.max(1)).headOption
        firstVersion <- order.headOption
      } yield firstVersion.value
      case GetStateFailure(cause) =>
        None
    }

  def pickItem(order: Order, i: Int): Option[String] =
    order.items.drop((i - 1) % order.items.size.max(1)).headOption

  protected def sendCommand(command: OrderCommand, destination: OrderLocation, i: Int): Unit = {
    log.info(s"------- Send ${command.getClass.getSimpleName} to ${destination.locationId} ($i/${Action.total}) --------")
    destination.manager ! command
  }
}

object Action {
  val total = 199
}

object CreateOrderAction extends Action {
  override def execute(location: OrderLocation, i: Int): Unit =
    sendCommand(CreateOrder(newOrderId), location, i)
}

object AddOrderItemAction extends Action {
  override def execute(location: OrderLocation, i: Int): Unit =
    pickOrder(location, i).foreach { order =>
      sendCommand(AddOrderItem(order.id, generateItem), location, i)
    }

  private def generateItem: String = Random.alphanumeric.take(5).mkString
}

object CancelOrderAction extends Action {
  override def execute(location: OrderLocation, i: Int) =
    pickOrder(location, i).foreach { order =>
      sendCommand(CancelOrder(order.id), location, i)
    }
}

object RemoveOrderItemAction extends Action {
  override def execute(location: OrderLocation, i: Int): Unit = for {
    order <- pickOrder(location, i)
    item <- pickItem(order, i)
  } sendCommand(RemoveOrderItem(order.id, item), location, i)
}