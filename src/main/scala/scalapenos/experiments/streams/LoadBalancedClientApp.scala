package scalapenos.experiments.streams

import java.io.File

import scala.concurrent.duration._
import scala.concurrent._
import scala.util._

import akka._
import akka.actor.ActorSystem

import akka.http.scaladsl._
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling._

import Uri._

import akka.stream._
import akka.stream.scaladsl._

object LoadBalancedClientApp extends App {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val dispatcher = system.dispatcher

  Await.result(responseStreamFuture(5, List(5000, 5001)), 1.minute)
  Await.ready(Http().shutdownAllConnectionPools(), 1.second)
  Await.result(system.terminate(), 1.seconds)

  println(s"Done.")

  private def responseStreamFuture(nrOfRequests: Int, ports: Seq[Int]): Future[Done] =
    requests(nrOfRequests)
      .via(loadBalancedFlow(ports))
      .runForeach {
        case (response, id) ⇒ {
          val responseAsString = Await.result(Unmarshal(response.get.entity).to[String], 1.second)
          println(s"${id}: Response = ${responseAsString}")
        }
      }

  private def requests(nr: Int): Source[(HttpRequest, Int), NotUsed] = {
    val reqs = (1 to nr).map { id ⇒
      HttpRequest(uri = "/twitter") → id
    }

    Source(reqs)
  }

  private def poolClientFlow(port: Int): Flow[(HttpRequest, Int), (Try[HttpResponse], Int), Http.HostConnectionPool] =
    Http().cachedHostConnectionPool[Int](host = "localhost", port = port)

  private def loadBalancedFlow(ports: Seq[Int]): Flow[(HttpRequest, Int), (Try[HttpResponse], Int), NotUsed] = {
    val workers = ports.map { port ⇒
      Flow[(HttpRequest, Int)]
        .map {
          case in @ (request, id) ⇒ {
            println(s"${id}: Sending request to port $port")
            in
          }
        }
        .via(Http().cachedHostConnectionPool[Int](host = "localhost", port = port).mapMaterializedValue(_ ⇒ NotUsed))
    }

    balancer(workers)
  }

  private def balancer[In, Out](workers: Seq[Flow[In, Out, Any]]): Flow[In, Out, NotUsed] = {
    import GraphDSL.Implicits._

    Flow.fromGraph(GraphDSL.create() { implicit b ⇒
      val balancer = b.add(Balance[In](workers.size, waitForAllDownstreams = true))
      val merge = b.add(Merge[Out](workers.size))

      workers.foreach { worker ⇒
        balancer ~> worker ~> merge
      }

      FlowShape(balancer.in, merge.out)
    })
  }

}
