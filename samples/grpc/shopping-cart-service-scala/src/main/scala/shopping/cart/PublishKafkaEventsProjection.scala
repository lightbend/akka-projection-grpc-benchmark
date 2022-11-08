package shopping.cart

import akka.Done
import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.kafka.ProducerMessage.Message
import akka.kafka.ProducerSettings
import akka.kafka.scaladsl.Producer
import akka.persistence.query.Offset
import akka.persistence.query.typed.EventEnvelope
import akka.persistence.r2dbc.query.javadsl.R2dbcReadJournal
import akka.projection.Projection
import akka.projection.ProjectionBehavior
import akka.projection.ProjectionContext
import akka.projection.ProjectionId
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.r2dbc.scaladsl.R2dbcProjection
import akka.projection.scaladsl.SourceProvider
import akka.stream.scaladsl.FlowWithContext
import com.google.protobuf.any.{ Any => ScalaPBAny }
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer

object PublishKafkaEventsProjection {

  def init(system: ActorSystem[_]): Unit = {
    val topic =
      system.settings.config.getString("shopping-cart-service.kafka.topic")

    val numberOfSliceRanges: Int = 16
    val sliceRanges = EventSourcedProvider.sliceRanges(
      system,
      R2dbcReadJournal.Identifier,
      numberOfSliceRanges)

    ShardedDaemonProcess(system).init(
      name = "PublishKafkaEventsProjection",
      numberOfInstances = sliceRanges.size,
      index =>
        ProjectionBehavior(
          createProjectionFor(system, topic, sliceRanges(index))),
      ShardedDaemonProcessSettings(system),
      Some(ProjectionBehavior.Stop))
  }

  private def createProjectionFor(
      system: ActorSystem[_],
      topic: String,
      sliceRange: Range): Projection[EventEnvelope[ShoppingCart.Event]] = {
    val minSlice = sliceRange.min
    val maxSlice = sliceRange.max
    val projectionId =
      ProjectionId("PublishKafkaEventsProjection", s"carts-$minSlice-$maxSlice")

    val sourceProvider
        : SourceProvider[Offset, EventEnvelope[ShoppingCart.Event]] =
      EventSourcedProvider.eventsBySlices[ShoppingCart.Event](
        system = system,
        readJournalPluginId = R2dbcReadJournal.Identifier,
        ShoppingCart.EntityKey.name,
        minSlice,
        maxSlice)

    val producerSettings =
      ProducerSettings(system, new StringSerializer, new ByteArraySerializer)

    val flow =
      FlowWithContext[EventEnvelope[ShoppingCart.Event], ProjectionContext]
        .map { envelope =>
          val event = envelope.event
          // using the cartId as the key and `DefaultPartitioner` will select partition based on the key
          // so that events for same cart always ends up in same partition
          val key = event.cartId
          Message(
            record = new ProducerRecord(
              topic,
              null,
              envelope.timestamp,
              key,
              serialize(event)),
            passThrough = NotUsed)
        }
        .via(Producer.flowWithContext(producerSettings))
        .map(_ => Done)

    R2dbcProjection.atLeastOnceFlow(
      projectionId,
      None,
      sourceProvider,
      handler = flow)(system)
  }

  private def serialize(event: ShoppingCart.Event): Array[Byte] = {
    val protoMessage = event match {
      case ShoppingCart.ItemAdded(cartId, itemId, quantity) =>
        proto.ItemAdded(cartId, itemId, quantity)

      case ShoppingCart.ItemQuantityAdjusted(cartId, itemId, quantity, _) =>
        proto.ItemQuantityAdjusted(cartId, itemId, quantity)
      case ShoppingCart.ItemRemoved(cartId, itemId, _) =>
        proto.ItemRemoved(cartId, itemId)

      case ShoppingCart.CheckedOut(cartId, _) =>
        proto.CheckedOut(cartId)
    }
    // pack in Any so that type information is included for deserialization
    ScalaPBAny.pack(protoMessage, "shopping-cart-service").toByteArray
  }

}
