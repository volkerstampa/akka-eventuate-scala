package sample.eventuate

import akka.actor.ActorLogging
import com.rbmhtechnology.eventuate.EventsourcedView

trait OrderLogging extends ActorLogging { this: EventsourcedView =>

  def replicaId: String

  def info(msg: String): Unit = if(!recovering) log.info(s"[$replicaId]: $msg")
}
