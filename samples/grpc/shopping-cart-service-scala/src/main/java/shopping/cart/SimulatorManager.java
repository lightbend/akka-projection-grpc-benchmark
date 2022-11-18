package shopping.cart;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;

import java.time.Duration;

public class SimulatorManager {
  interface Command {
  }

  public static final class Start implements SimulatorManager.Command {
    public final int count;
    public final Duration delay;
    public final Duration initialDelay;

    public Start(int count, Duration delay, Duration initialDelay) {
      this.count = count;
      this.delay = delay;
      this.initialDelay = initialDelay;
    }
  }

  public static Behavior<Command> create() {
    return Behaviors.setup(ctx -> {
          int n = ctx.getSystem().settings().config().getInt("shopping-cart-service.simulator-count");
          if (n > 0) {
            Duration delay = ctx.getSystem().settings().config().getDuration("shopping-cart-service.simulator-delay");
            Duration initialDelay = ctx.getSystem().settings().config().getDuration("shopping-cart-service.simulator-initial-delay");
            ctx.getSelf().tell(new Start(n, delay, initialDelay));
          }

          return Behaviors.receive(Command.class)
              .onMessage(Start.class, start -> onStart(ctx, start))
              .build();
        }
    );
  }

  private static Behavior<Command> onStart(ActorContext<Command> ctx, Start start) {
    ctx.getChildren().forEach(ctx::stop);

    ctx.getLog().info("Starting {} simulators", start.count);

    for (int i = 0; i < start.count; i++) {
      ctx.spawnAnonymous(Simulator.create(start.delay, start.initialDelay));
    }
    return Behaviors.same();
  }
}
