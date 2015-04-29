package sample.eventuate

import akka.actor.{Actor, ActorLogging}
import com.rbmhtechnology.eventuate.Eventsourced

trait OrderLogging extends ActorLogging { this: Actor with Eventsourced =>

  def replicaId: String

  def info(msg: String): Unit = if(!recovering) log.info(s"[$replicaId]: $msg")
}
