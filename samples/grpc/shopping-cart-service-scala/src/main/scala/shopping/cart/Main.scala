package shopping.cart

import akka.actor.typed.ActorSystem
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import akka.projection.grpc.replication.ReplicationSettings
import akka.projection.grpc.replication.scaladsl.Replication
import akka.projection.grpc.replication.scaladsl.ReplicationProjectionProvider
import akka.projection.r2dbc.scaladsl.R2dbcProjection
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

    val (eventProducerHandler, entityKey) =
      if (system.settings.config.getBoolean(
          "replicated-shopping-cart.enabled")) {
        // Replicated Event Sourcing Shopping cart
        val replication = setUpReplication()(system)
        (replication.createSingleServiceHandler(), replication.entityTypeKey)
      } else {
        // Regular Event Sourced shopping cart
        ShoppingCart.init(system)
        if (system.settings.config.getBoolean(
            "shopping-cart-service.kafka.enabled"))
          PublishKafkaEventsProjection.init(system)

        (PublishEvents.eventProducerService(system), ShoppingCart.EntityKey)
      }

    val grpcInterface =
      system.settings.config.getString("shopping-cart-service.grpc.interface")
    val grpcPort =
      system.settings.config.getInt("shopping-cart-service.grpc.port")

    val shoppingCartService =
      new ShoppingCartServiceImpl(entityKey, system)
    val simulatorService = new SimulatorServiceImpl(system)
    ShoppingCartServer.start(
      grpcInterface,
      grpcPort,
      system,
      shoppingCartService,
      simulatorService,
      eventProducerHandler)
  }

  private def setUpReplication()(
      implicit system: ActorSystem[_]): Replication[ShoppingCart.Command] = {
    val projectionProvider: ReplicationProjectionProvider =
      R2dbcProjection.atLeastOnceFlow(_, None, _, _)(_)
    val replicationSettings =
      ReplicationSettings[ShoppingCart.Command](
        "replicated-shopping-cart",
        projectionProvider)

    Replication.grpcReplication[
      ShoppingCart.Command,
      ReplicatedShoppingCart.Event,
      ReplicatedShoppingCart.State](replicationSettings)(
      ReplicatedShoppingCart.apply)(system)
  }

}
