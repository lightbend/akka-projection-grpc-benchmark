package shopping.cart

import akka.actor.typed.ActorSystem
import akka.grpc.GrpcClientSettings
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import akka.persistence.typed.ReplicaId
import akka.projection.grpc.consumer.GrpcQuerySettings
import akka.projection.grpc.producer.EventProducerSettings
import akka.projection.grpc.replication.Replica
import akka.projection.grpc.replication.ReplicationSettings
import akka.projection.grpc.replication.scaladsl.Replication
import com.typesafe.config.Config
import org.slf4j.LoggerFactory

import scala.jdk.CollectionConverters._
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
          "shopping-cart-service.replication.enabled")) {
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
    // FIXME from config
    val selfReplicaId = ReplicaId(
      system.settings.config
        .getString("shopping-cart-service.replication.self-replica-id"))

    val allReplicas = system.settings.config
      .getConfigList("shopping-cart-service.replication.replicas")
      .asScala
      .toSet
      .map { config: Config =>
        val replicaId = config.getString("replica-id")
        Replica(
          ReplicaId(replicaId),
          numberOfConsumers = config.getInt("number-of-consumers"),
          querySettings = GrpcQuerySettings(system), // FIXME ok to share?
          // so akka.grpc.client.[replica-id]
          grpcClientSettings = GrpcClientSettings.fromConfig(replicaId))
      }

    val replicationSettings = ReplicationSettings[ShoppingCart.Command](
      "cart",
      selfReplicaId,
      EventProducerSettings(system),
      allReplicas.filter(_.replicaId != selfReplicaId))
    Replication.grpcReplication[
      ShoppingCart.Command,
      ReplicatedShoppingCart.Event,
      ReplicatedShoppingCart.State](replicationSettings)(
      ReplicatedShoppingCart.apply)(system)
  }

}
