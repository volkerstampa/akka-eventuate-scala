package sample.eventuate

import akka.actor.{ActorSystem, Props}
import com.rbmhtechnology.eventuate.ReplicationConnection.DefaultRemoteSystemName
import com.rbmhtechnology.eventuate.ReplicationEndpoint
import com.rbmhtechnology.eventuate.ReplicationEndpoint.DefaultLogName
import com.rbmhtechnology.eventuate.log.leveldb.LeveldbEventLog
import com.typesafe.config.ConfigFactory

class OrderLocation(val locationId: String) {
  val system = ActorSystem(DefaultRemoteSystemName, ConfigFactory.load(locationId))
  val endpoint = ReplicationEndpoint(id => LeveldbEventLog.props(id))(system)
  val manager = system.actorOf(
    Props(new OrderManager(endpoint.id, endpoint.logs(DefaultLogName))))
  val view = system.actorOf(Props(new OrderView(endpoint.id, endpoint.logs(DefaultLogName))))
}
