import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream._
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge}

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.DurationInt
import scala.io.StdIn
import scala.util.{Failure, Success}

object Main {

  def main(args: Array[String]) {

    implicit val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "testproxy")
    implicit val executionContext: ExecutionContextExecutor = system.executionContext
    system.log.info("TestAkkaHttpProxy Main started...")
    val remoteHost = "xxx.xxx.xxx.x"
    val remotePort = 8000
    val proxyHost = "0.0.0.0"
    val proxyPort = 8080

    val gateway = Flow.fromGraph(GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._

      // Broadcast for flow input
      val broadcaster = b.add(Broadcast[HttpRequest](1))
      // Merge for flow output
      val responseMerge = b.add(Merge[HttpResponse](1))
      // outgoing client for remote proxy
      val remote = Http().outgoingConnection(remoteHost, remotePort)
      // filter out header that creates Akka Http warning
      val requestConvert = Flow[HttpRequest]
        .map(req => { req.mapHeaders(headers => headers.filter(h => h.isNot("timeout-access")))
        })
      // connect graph
      broadcaster.out(0) ~> requestConvert ~> remote ~> responseMerge
      // expose ports
      FlowShape(broadcaster.in, responseMerge.out)
    })

    // Akka Http server that binds to Flow (for remote proxy)
    Http().newServerAt(proxyHost, proxyPort).bindFlow(gateway)
      .onComplete({
        case Success(binding) ⇒
          println(s"Server is listening on 0.0.0.0:8080")
          binding.addToCoordinatedShutdown(hardTerminationDeadline = 10.seconds)
        case Failure(e) ⇒
          println(s"Binding failed with ${e.getMessage}")
          system.terminate()
      })

    system.log.info("Press RETURN to stop...")
    StdIn.readLine()
    system.terminate()
  }
}
