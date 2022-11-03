package shopping.analytics

import akka.Done
import akka.actor.typed.ActorSystem
import akka.kafka.scaladsl.{ Committer, Consumer }
import akka.kafka.{ CommitterSettings, ConsumerSettings, Subscriptions }
import akka.stream.RestartSettings
import akka.stream.scaladsl.RestartSource
import com.google.protobuf.any.{ Any => ScalaPBAny }
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.{
  ByteArrayDeserializer,
  StringDeserializer
}
import org.slf4j.LoggerFactory
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import scala.util.control.NonFatal

import akka.cluster.typed.Cluster
import shoppingcart.CheckedOut
import shoppingcart.ItemAdded
import shoppingcart.ItemQuantityAdjusted
import shoppingcart.ItemRemoved

object KafkaShoppingCartEventConsumer {

  private val log =
    LoggerFactory.getLogger("shopping.analytics.KafkaShoppingCartEventConsumer")

  def init(system: ActorSystem[_]): Unit = {
    implicit val sys: ActorSystem[_] = system
    implicit val ec: ExecutionContext =
      system.executionContext

    val topic =
      system.settings.config.getString("shopping-analytics-service.kafka.topic")
    val consumerSettings =
      ConsumerSettings(
        system,
        new StringDeserializer,
        new ByteArrayDeserializer).withGroupId("shopping-cart-analytics")
    val committerSettings = CommitterSettings(system)

    RestartSource
      .onFailuresWithBackoff(
        RestartSettings(
          minBackoff = 1.second,
          maxBackoff = 5.seconds,
          randomFactor = 0.1)) { () =>
        val handler = new Handler(system)
        Consumer
          .committableSource(consumerSettings, Subscriptions.topics(topic))
          .mapAsync(1) { msg =>
            handler.process(msg.record).map(_ => msg.committableOffset)
          }
          .via(Committer.flow(committerSettings))
      }
      .run()
  }

  class Handler(system: ActorSystem[_]) extends Measure {

    override val logId: String = Cluster(system).selfMember.address.hostPort

    def process(record: ConsumerRecord[String, Array[Byte]]): Future[Done] = {
      val bytes = record.value()
      val x = ScalaPBAny.parseFrom(bytes)
      val typeUrl = x.typeUrl
      try {
        val inputBytes = x.value.newCodedInput()
        val event =
          typeUrl match {
            case "shopping-cart-service/shoppingcart.ItemAdded" =>
              ItemAdded.parseFrom(inputBytes)

            case "shopping-cart-service/shoppingcart.ItemQuantityAdjusted" =>
              ItemQuantityAdjusted.parseFrom(inputBytes)
            case "shopping-cart-service/shoppingcart.ItemRemoved" =>
              ItemRemoved.parseFrom(inputBytes)

            case "shopping-cart-service/shoppingcart.CheckedOut" =>
              CheckedOut.parseFrom(inputBytes)
            case _ =>
              throw new IllegalArgumentException(
                s"unknown record type [$typeUrl]")
          }

        event match {
          case ItemAdded(cartId, itemId, quantity, _) =>
            log.debug("ItemAdded: {} {} to cart {}", quantity, itemId, cartId)

          case ItemQuantityAdjusted(cartId, itemId, quantity, _) =>
            log.debug(
              "ItemQuantityAdjusted: {} {} to cart {}",
              quantity,
              itemId,
              cartId)
          case ItemRemoved(cartId, itemId, _) =>
            log.debug("ItemRemoved: {} removed from cart {}", itemId, cartId)

          case CheckedOut(cartId, _) =>
            log.debug("CheckedOut: cart {} checked out", cartId)
        }

        processedEvent(record.timestamp())

        Future.successful(Done)
      } catch {
        case NonFatal(e) =>
          log.error("Could not process event of type [{}]", typeUrl, e)
          // continue with next
          Future.successful(Done)
      }
    }
  }

}
