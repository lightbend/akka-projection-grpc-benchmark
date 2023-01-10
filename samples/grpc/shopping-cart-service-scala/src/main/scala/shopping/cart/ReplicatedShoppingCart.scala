/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */
package shopping.cart

import akka.pattern.StatusReply
import akka.persistence.typed.crdt.Counter
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.persistence.typed.scaladsl.ReplicationContext
import akka.persistence.typed.scaladsl.ReplyEffect

import java.time.Instant

object ReplicatedShoppingCart {

  // shares protocol with ShoppingCart
  import ShoppingCart.{
    AddItem,
    AdjustItemQuantity,
    Checkout,
    Command,
    Get,
    RemoveItem,
    Summary
  }

  sealed trait Event extends CborSerializable

  final case class ItemUpdated(
      cartId: String,
      productId: String,
      update: Counter.Updated)
      extends Event

  final case class CheckedOut(chartId: String, eventTime: Instant) extends Event

  final case class State(
      items: Map[String, Counter],
      checkoutDate: Option[Instant]) {
    def isCheckedOut: Boolean =
      checkoutDate.isDefined

    def hasItem(itemId: String): Boolean =
      items.contains(itemId)

    def isEmpty: Boolean =
      items.isEmpty

    def updateItem(itemId: String, counter: Counter): State =
      copy(items = items.updated(itemId, counter))

    def removeItem(itemId: String): State =
      copy(items = items - itemId)

    def checkout(now: Instant): State =
      copy(checkoutDate = Some(now))

    def toSummary: Summary =
      Summary(
        items.collect {
          case (itemId, counter) if counter.value > 0 =>
            itemId -> counter.value.toInt
        },
        isCheckedOut)
  }

  object State {
    val empty = State(Map.empty, None)
  }

  def apply(replicationContext: ReplicationContext)
      : EventSourcedBehavior[Command, Event, State] = {
    EventSourcedBehavior(
      replicationContext.persistenceId,
      emptyState = State.empty,
      commandHandler = (state, command) =>
        handleCommand(replicationContext.entityId, state, command),
      eventHandler = (state, event) => handleEvent(state, event))
  }

  private def handleCommand(
      cartId: String,
      state: State,
      command: Command): ReplyEffect[Event, State] = {
    // The shopping cart behavior changes if it's checked out or not.
    // The commands are handled differently for each case.
    if (state.isCheckedOut)
      checkedOutShoppingCart(cartId, state, command)
    else
      openShoppingCart(cartId, state, command)
  }

  private def openShoppingCart(
      cartId: String,
      state: State,
      command: Command): ReplyEffect[Event, State] = {
    command match {
      case AddItem(itemId, quantity, replyTo) =>
        if (state.hasItem(itemId))
          Effect.reply(replyTo)(
            StatusReply.Error(
              s"Item '$itemId' was already added to this shopping cart"))
        else if (quantity <= 0)
          Effect.reply(replyTo)(
            StatusReply.Error("Quantity must be greater than zero"))
        else
          Effect
            .persist(ItemUpdated(cartId, itemId, Counter.Updated(quantity)))
            .thenReply(replyTo) { updatedCart =>
              StatusReply.Success(updatedCart.toSummary)
            }

      case RemoveItem(itemId, replyTo) =>
        if (state.hasItem(itemId))
          Effect
            .persist(
              ItemUpdated(
                cartId,
                itemId,
                Counter.Updated(-state.items(itemId).value)))
            .thenReply(replyTo)(updatedCart =>
              StatusReply.Success(updatedCart.toSummary))
        else
          Effect.reply(replyTo)(
            StatusReply.Success(state.toSummary)
          ) // removing an item is idempotent

      case AdjustItemQuantity(itemId, quantity, replyTo) =>
        if (quantity <= 0)
          Effect.reply(replyTo)(
            StatusReply.Error("Quantity must be greater than zero"))
        else if (state.hasItem(itemId))
          Effect
            .persist(ItemUpdated(cartId, itemId, Counter.Updated(quantity)))
            .thenReply(replyTo)(updatedCart =>
              StatusReply.Success(updatedCart.toSummary))
        else
          Effect.reply(replyTo)(StatusReply.Error(
            s"Cannot adjust quantity for item '$itemId'. Item not present on cart"))

      case Checkout(replyTo) =>
        if (state.isEmpty)
          Effect.reply(replyTo)(
            StatusReply.Error("Cannot checkout an empty shopping cart"))
        else
          Effect
            .persist(CheckedOut(cartId, Instant.now()))
            .thenReply(replyTo)(updatedCart =>
              StatusReply.Success(updatedCart.toSummary))

      case Get(replyTo) =>
        Effect.reply(replyTo)(state.toSummary)
    }
  }

  private def checkedOutShoppingCart(
      cartId: String,
      state: State,
      command: Command): ReplyEffect[Event, State] = {
    command match {
      case Get(replyTo) =>
        Effect.reply(replyTo)(state.toSummary)
      case cmd: AddItem =>
        Effect.reply(cmd.replyTo)(
          StatusReply.Error(
            "Can't add an item to an already checked out shopping cart"))
      case cmd: RemoveItem =>
        Effect.reply(cmd.replyTo)(
          StatusReply.Error(
            "Can't remove an item from an already checked out shopping cart"))
      case cmd: AdjustItemQuantity =>
        Effect.reply(cmd.replyTo)(
          StatusReply.Error(
            "Can't adjust item on an already checked out shopping cart"))
      case cmd: Checkout =>
        Effect.reply(cmd.replyTo)(
          StatusReply.Error("Can't checkout already checked out shopping cart"))
    }
  }

  private def handleEvent(state: State, event: Event): State = {
    event match {
      case ItemUpdated(_, itemId, update) =>
        state.updateItem(
          itemId,
          state.items.getOrElse(itemId, Counter.empty).applyOperation(update))
      case CheckedOut(_, eventTime) =>
        // first check out wins, subsequent are ignored
        if (state.isCheckedOut) state
        else state.checkout(eventTime)
    }
  }

}
