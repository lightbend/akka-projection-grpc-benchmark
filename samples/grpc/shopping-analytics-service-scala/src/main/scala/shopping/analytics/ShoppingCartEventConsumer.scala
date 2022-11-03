package shopping.analytics

//#initProjections
import scala.concurrent.Future

import akka.Done
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.persistence.Persistence
import akka.persistence.query.typed.EventEnvelope
import akka.projection.ProjectionBehavior
import akka.projection.ProjectionId
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.grpc.consumer.scaladsl.GrpcReadJournal
import akka.projection.r2dbc.scaladsl.R2dbcProjection
import akka.projection.scaladsl.Handler
import org.slf4j.LoggerFactory
import shoppingcart.CheckedOut
import shoppingcart.ItemAdded
import shoppingcart.ItemQuantityAdjusted
import shoppingcart.ItemRemoved
import shoppingcart.ShoppingCartEventsProto

object ShoppingCartEventConsumer {
  //#initProjections

  private val log =
    LoggerFactory.getLogger("shopping.analytics.ShoppingCartEventConsumer")

  //#eventHandler
  private class EventHandler(projectionId: ProjectionId)
      extends Handler[EventEnvelope[AnyRef]]
      with Measure {

    override val logId: String = projectionId.id

    override def start(): Future[Done] = {
      log.info("Started Projection [{}].", projectionId.id)
      super.start()
    }
    override def stop(): Future[Done] = {
      log.info("Stopped Projection [{}]", projectionId.id)
      super.stop()
    }

    override def process(envelope: EventEnvelope[AnyRef]): Future[Done] = {
      val event = envelope.event

      event match {
        case itemAdded: ItemAdded =>
          log.debug(
            "Projection [{}] consumed ItemAdded for cart {}, added {} {}. Total [{}] events.",
            projectionId.id,
            itemAdded.cartId,
            itemAdded.quantity,
            itemAdded.itemId)
        case quantityAdjusted: ItemQuantityAdjusted =>
          log.debug(
            "Projection [{}] consumed ItemQuantityAdjusted for cart {}, changed {} {}. Total [{}] events.",
            projectionId.id,
            quantityAdjusted.cartId,
            quantityAdjusted.quantity,
            quantityAdjusted.itemId)
        case itemRemoved: ItemRemoved =>
          log.debug(
            "Projection [{}] consumed ItemRemoved for cart {}, removed {}. Total [{}] events.",
            projectionId.id,
            itemRemoved.cartId,
            itemRemoved.itemId)
        case checkedOut: CheckedOut =>
          log.debug(
            "Projection [{}] consumed CheckedOut for cart {}. Total [{}] events.",
            projectionId.id,
            checkedOut.cartId)
        case unknown =>
          throw new IllegalArgumentException(s"Unknown event $unknown")
      }

      processedEvent(envelope.timestamp)

      Future.successful(Done)
    }
  }
  //#eventHandler

  //#initProjections
  def init(system: ActorSystem[_]): Unit = {
    implicit val sys: ActorSystem[_] = system
    val numberOfProjectionInstances = 16
    val projectionName: String = "cart-events"
    val sliceRanges =
      Persistence(system).sliceRanges(numberOfProjectionInstances)

    val eventsBySlicesQuery =
      GrpcReadJournal(List(ShoppingCartEventsProto.javaDescriptor))

    ShardedDaemonProcess(system).init(
      projectionName,
      numberOfProjectionInstances,
      { idx =>
        val sliceRange = sliceRanges(idx)
        val projectionKey =
          s"${eventsBySlicesQuery.streamId}-${sliceRange.min}-${sliceRange.max}"
        val projectionId = ProjectionId.of(projectionName, projectionKey)

        val sourceProvider = EventSourcedProvider.eventsBySlices[AnyRef](
          system,
          eventsBySlicesQuery,
          eventsBySlicesQuery.streamId,
          sliceRange.min,
          sliceRange.max)

        ProjectionBehavior(
          R2dbcProjection.atLeastOnceAsync(
            projectionId,
            None,
            sourceProvider,
            () => new EventHandler(projectionId)))
      })
  }

}
//#initProjections
