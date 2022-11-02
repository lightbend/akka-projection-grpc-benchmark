package shopping.cart;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.javadsl.TimerScheduler;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.EntityRef;
import akka.cluster.sharding.typed.javadsl.EntityTypeKey;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class Simulator extends AbstractBehavior<Simulator.Command> {
 // Defining this here because mixing java and Scala
 private static final EntityTypeKey<ShoppingCart.Command> ENTITY_KEY =
     EntityTypeKey.create(ShoppingCart.Command.class, ShoppingCart.EntityKey().name());

 interface Command extends CborSerializable {
 }

 public static final class NextCart implements Simulator.Command {
  public NextCart() {
  }
 }

 public static final class Next implements Simulator.Command {
  public final int n;

  public Next(int n) {
   this.n = n;
  }
 }

 public static final class Delay implements Simulator.Command {
  public Delay() {
  }
 }

 public static Behavior<Void> createMany() {
  return Behaviors.setup(ctx -> {
       int n = ctx.getSystem().settings().config().getInt("shopping-cart-service.simulator-count");
       for (int i = 0; i < n; i++) {
        ctx.spawn(create(), "simulator-" + i);
       }
       return Behaviors.empty();
      }
  );
 }

 public static Behavior<Simulator.Command> create() {
  return Behaviors.setup(ctx ->
      Behaviors.withTimers(timers ->
          new Simulator(ctx, timers)));
 }

 private final TimerScheduler<Command> timers;
 private final ClusterSharding sharding;
 private final Duration timeout;
 private final Duration delay;

 private String cart = "";

 public Simulator(ActorContext<Command> ctx, TimerScheduler<Command> timers) {
  super(ctx);
  this.timers = timers;
  sharding = ClusterSharding.get(ctx.getSystem());
  timeout = ctx.getSystem().settings().config().getDuration("shopping-cart-service.ask-timeout");
  delay = ctx.getSystem().settings().config().getDuration("shopping-cart-service.simulator-delay");

  ctx.setReceiveTimeout(Duration.ofSeconds(10), new NextCart());
  timers.startSingleTimer(new NextCart(), Duration.ofMillis(5000));
 }

 @Override
 public Receive<Command> createReceive() {
  return newReceiveBuilder()
      .onMessage(Delay.class, this::onDelay)
      .onMessage(NextCart.class, this::onNextCart)
      .onMessage(Next.class, this::onNext)
      .build();
 }

 private Behavior<Command> onDelay(Delay d) {
  timers.startSingleTimer(new NextCart(), delay);
  return this;
 }

 private Behavior<Command> onNextCart(NextCart next) {
  cart = UUID.randomUUID().toString();
  EntityRef<ShoppingCart.Command> ref = sharding.entityRefFor(ENTITY_KEY, cart);
  getContext().askWithStatus(ShoppingCart.Summary.class, ref, timeout, replyTo -> new ShoppingCart.AddItem("t-shirt", 1, replyTo), (summary, exc) -> new Next(1));
  return this;
 }

 private Behavior<Command> onNext(Next next) {
  EntityRef<ShoppingCart.Command> ref = sharding.entityRefFor(ENTITY_KEY, cart);

  if (next.n <= 5) {
   getContext().askWithStatus(ShoppingCart.Summary.class, ref, timeout,
       replyTo -> new ShoppingCart.AdjustItemQuantity("t-shirt", next.n + 1, replyTo),
       (summary, exc) -> new Next(next.n + 1));
  } else {
   getContext().askWithStatus(ShoppingCart.Summary.class, ref, timeout,
       ShoppingCart.Checkout::new,
       (summary, exc) -> new Delay());
  }

  return this;
 }

}
