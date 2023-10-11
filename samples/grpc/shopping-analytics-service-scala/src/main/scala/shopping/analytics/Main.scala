package shopping.analytics

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.ActorSystem
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import org.slf4j.LoggerFactory
import scala.util.control.NonFatal

object Main {

  val logger = LoggerFactory.getLogger("shopping.analytics.Main")

  def main(args: Array[String]): Unit = {
    val system =
      ActorSystem[Nothing](Behaviors.empty, "ShoppingAnalyticsService")
    try {
      init(system)
    } catch {
      case NonFatal(e) =>
        logger.error("Terminating due to initialization failure.", e)
        system.terminate()
    }
  }

  def init(system: ActorSystem[_]): Unit = {
    AkkaManagement(system).start()
    ClusterBootstrap(system).start()

    system.settings.config.getString(
      "shopping-analytics-service.event-replication-mechanism") match {
      case "kafka" =>
        KafkaShoppingCartEventConsumer.init(system)
      case "akka-projection-grpc" =>
        ShoppingCartEventConsumer.init(system)
      case "akka-projection-grpc-push" =>
        ShoppingCartEventPushDestination.startGrpcServer()(system)
        ShoppingCartEventPushDestination.startLocalProjection()(system)
      case unknown =>
        throw new IllegalArgumentException(s"Unknown mechanism '$unknown")
    }
  }

}
