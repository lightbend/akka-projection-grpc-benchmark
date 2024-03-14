package shopping.cart

import akka.actor.typed.{ ActorSystem, Behavior }
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.grpc.GrpcClientSettings
import akka.persistence.Persistence
import akka.persistence.query.Offset
import akka.persistence.query.typed.EventEnvelope
import akka.persistence.r2dbc.query.javadsl.R2dbcReadJournal
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.grpc.producer.EventProducerSettings
import akka.projection.grpc.producer.scaladsl.EventProducer.Transformation
import akka.projection.grpc.producer.scaladsl.{
  EventProducer,
  EventProducerPush
}
import akka.projection.r2dbc.scaladsl.R2dbcProjection
import akka.projection.{ ProjectionBehavior, ProjectionId }

object PublishPushEvents {

  private val StreamId = "cart-events"

  def startEventProducerPush()(implicit system: ActorSystem[_]): Unit = {
    val nrOfEventProducers = 16

    val transformation = Transformation.empty
      .registerMapper[ShoppingCart.ItemAdded, proto.ItemAdded](event =>
        Some(transformItemAdded(event)))
      .registerMapper[
        ShoppingCart.ItemQuantityAdjusted,
        proto.ItemQuantityAdjusted](event =>
        Some(transformItemQuantityAdjusted(event)))
      .registerMapper[ShoppingCart.ItemRemoved, proto.ItemRemoved](event =>
        Some(transformItemRemoved(event)))
      .registerMapper[ShoppingCart.CheckedOut, proto.CheckedOut](event =>
        Some(transformCheckedOut(event)))

    val eventProducer = EventProducerPush[ShoppingCart.Event](
      originId = "cart",
      eventProducerSource = EventProducer.EventProducerSource(
        entityType = ShoppingCart.EntityKey.name,
        streamId = StreamId,
        transformation = transformation,
        settings = EventProducerSettings(system)),
      GrpcClientSettings.fromConfig("analytics-service"))

    val sliceRanges =
      Persistence(system).sliceRanges(nrOfEventProducers)

    def projectionForPartition(
        partition: Int): Behavior[ProjectionBehavior.Command] = {
      val sliceRange = sliceRanges(partition)
      val minSlice = sliceRange.min
      val maxSlice = sliceRange.max

      ProjectionBehavior(
        R2dbcProjection
          .atLeastOnceFlow[Offset, EventEnvelope[ShoppingCart.Event]](
            ProjectionId("cart-event-push", s"$minSlice-$maxSlice"),
            settings = None,
            sourceProvider =
              EventSourcedProvider.eventsBySlices[ShoppingCart.Event](
                system,
                R2dbcReadJournal.Identifier,
                eventProducer.eventProducerSource.entityType,
                minSlice,
                maxSlice),
            handler = eventProducer.handler()))

    }

    ShardedDaemonProcess(system).init(
      "CartEventPush",
      nrOfEventProducers,
      projectionForPartition)
  }

  private def transformItemAdded(
      added: ShoppingCart.ItemAdded): proto.ItemAdded =
    proto.ItemAdded(
      cartId = added.cartId,
      itemId = added.itemId,
      quantity = added.quantity)

  def transformItemQuantityAdjusted(
      event: ShoppingCart.ItemQuantityAdjusted): proto.ItemQuantityAdjusted =
    proto.ItemQuantityAdjusted(
      cartId = event.cartId,
      itemId = event.itemId,
      quantity = event.newQuantity)

  def transformItemRemoved(event: ShoppingCart.ItemRemoved): proto.ItemRemoved =
    proto.ItemRemoved(cartId = event.cartId, itemId = event.itemId)

  def transformCheckedOut(event: ShoppingCart.CheckedOut): proto.CheckedOut =
    proto.CheckedOut(event.cartId)

}
