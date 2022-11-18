package shopping.cart

import java.time.Duration

import scala.concurrent.Future

import akka.actor.typed.ActorRef
import com.google.protobuf.empty.Empty
import shopping.cart.proto.StartSimulatorsRequest

class SimulatorServiceImpl(simulators: ActorRef[SimulatorManager.Command])
    extends proto.SimulatorService {
  override def startSimulators(req: StartSimulatorsRequest): Future[Empty] = {
    simulators ! new SimulatorManager.Start(
      req.count,
      Duration.ofMillis(req.delayMillis),
      Duration.ofMillis(req.initialDelayMillis))
    Future.successful(Empty.defaultInstance)
  }
}
