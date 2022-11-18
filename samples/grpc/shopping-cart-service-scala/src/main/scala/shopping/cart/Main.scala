package shopping.cart

import akka.actor.typed.ActorSystem
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import org.slf4j.LoggerFactory
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
    if (system.settings.config.getBoolean(
        "shopping-cart-service.kafka.enabled"))
      PublishKafkaEventsProjection.init(system)

    val eventProducerService = PublishEvents.eventProducerService(system)

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
