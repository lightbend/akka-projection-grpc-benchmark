package shopping.cart

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.util.control.NonFatal

object Main {

  val logger = LoggerFactory.getLogger("shopping.cart.Main")

  def main(args: Array[String]): Unit = {
    val system =
      ActorSystem(SimulatorManager.create(), "ShoppingCartService")
    try {
      init(system)
    } catch {
      case NonFatal(e) =>
        logger.error("Terminating due to initialization failure.", e)
        system.terminate()
    }
  }

  def init(system: ActorSystem[SimulatorManager.Command]): Unit = {
    AkkaManagement(system).start()
    ClusterBootstrap(system).start()

    ShoppingCart.init(system)
    var eventProducerService
        : PartialFunction[HttpRequest, Future[HttpResponse]] =
      PartialFunction.empty

    system.settings.config
      .getString("shopping-cart-service.event-replication-mechanism") match {
      case "kafka" =>
        PublishKafkaEventsProjection.init(system)
      case "akka-projection-grpc-push" =>
        PublishPushEvents.startEventProducerPush()(system)
      case "akka-projection-grpc" =>
        eventProducerService = PublishEvents.eventProducerService(system)
      case unknown => throw new IllegalArgumentException(s"Unknown mechanism '$unknown")
    }

    val grpcInterface =
      system.settings.config.getString("shopping-cart-service.grpc.interface")
    val grpcPort =
      system.settings.config.getInt("shopping-cart-service.grpc.port")
    val shoppingCartService = new ShoppingCartServiceImpl(system)
    val simulatorService = new SimulatorServiceImpl(system)
    ShoppingCartServer.start(
      grpcInterface,
      grpcPort,
      system,
      shoppingCartService,
      simulatorService,
      eventProducerService)
  }

}
