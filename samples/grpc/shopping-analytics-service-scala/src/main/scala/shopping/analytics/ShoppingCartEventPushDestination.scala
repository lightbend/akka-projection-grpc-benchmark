package shopping.analytics

import akka.Done
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.grpc.scaladsl.ServiceHandler
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.persistence.Persistence
import akka.persistence.query.Offset
import akka.persistence.query.typed.EventEnvelope
import akka.persistence.query.typed.scaladsl.EventsBySliceFirehoseQuery
import akka.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import akka.projection.{ Projection, ProjectionBehavior, ProjectionId }
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.grpc.consumer.scaladsl.EventProducerPushDestination
import akka.projection.r2dbc.scaladsl.R2dbcProjection
import akka.projection.scaladsl.{ Handler, SourceProvider }
import org.slf4j.LoggerFactory
import shoppingcart.{ CheckedOut, ItemAdded, ItemQuantityAdjusted, ItemRemoved }

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

object ShoppingCartEventPushDestination {

  private val log =
    LoggerFactory.getLogger(
      "shopping.analytics.ShoppingCartEventPushDestination")

  private val StreamId = "cart-events"

  private val CartEntityType = "AnalyticsCart"

  def startGrpcServer()(implicit system: ActorSystem[_]): Unit = {
    implicit val ec: ExecutionContext =
      system.executionContext

    val measure = new Measure {
      override def logId: String = "event-producer-push-destination"
    }
    val destination = EventProducerPushDestination(
      StreamId,
      Seq(
        shoppingcart.ItemAdded.javaDescriptor.getFile,
        shoppingcart.ItemRemoved.javaDescriptor.getFile,
        shoppingcart.ItemQuantityAdjusted.javaDescriptor.getFile,
        shoppingcart.CheckedOut.javaDescriptor.getFile)).withTransformation(
      EventProducerPushDestination.Transformation.empty
        .registerPersistenceIdMapper { envelope =>
          // rewrite type to be able to share the same db when running locally
          measure.processedEvent(envelope.timestamp)
          envelope.persistenceId.replace("ShoppingCart", CartEntityType)
        })
    val handler = EventProducerPushDestination.grpcServiceHandler(destination)

    val service: HttpRequest => Future[HttpResponse] =
      ServiceHandler.concatOrNotFound(handler)

    val grpcInterface =
      system.settings.config
        .getString("shopping-analytics-service.grpc.interface")
    val grpcPort =
      system.settings.config.getInt("shopping-analytics-service.grpc.port")

    val bound =
      Http()
        .newServerAt(grpcInterface, grpcPort)
        .bind(service)
        .map(_.addToCoordinatedShutdown(3.seconds))

    bound.onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        system.log.info(
          "Shopping analytics gRPC server push destination online at {}:{}",
          address.getHostString,
          address.getPort)
      case Failure(ex) =>
        system.log.error("Failed to bind gRPC endpoint, terminating system", ex)
        system.terminate()
    }

  }

  // Note:  this "Measure" will be about write to local journal until projection sees it
  //        not the full lag from producer write to consumer projection sees it
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

  def startLocalProjection()(implicit system: ActorSystem[_]): Unit = {
    val numberOfProjectionInstances = 16
    val sliceRanges =
      Persistence(system).sliceRanges(numberOfProjectionInstances)

    def sourceProvider(sliceRange: Range): SourceProvider[
      Offset,
      // actually protobuf messages in db
      EventEnvelope[AnyRef]] =
      EventSourcedProvider.eventsBySlices[AnyRef](
        system,
        readJournalPluginId = R2dbcReadJournal.Identifier,
        CartEntityType,
        sliceRange.min,
        sliceRange.max)

    def projection(sliceRange: Range): Projection[EventEnvelope[AnyRef]] = {
      val minSlice = sliceRange.min
      val maxSlice = sliceRange.max

      val projectionId =
        ProjectionId(
          "AnalyticCartEvents",
          s"analytics-cart-$minSlice-$maxSlice")

      R2dbcProjection.atLeastOnceAsync(
        projectionId,
        settings = None,
        sourceProvider(sliceRange),
        handler = () => new EventHandler(projectionId))
    }

    ShardedDaemonProcess(system).init(
      name = "LocalCartProjection",
      numberOfInstances = sliceRanges.size,
      behaviorFactory = i => ProjectionBehavior(projection(sliceRanges(i))),
      stopMessage = ProjectionBehavior.Stop)

  }
}
