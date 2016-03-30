package scalapenos.experiments.streams

import scala.concurrent.duration._
import scala.concurrent._
import scala.util._

import akka._
import akka.actor._

import akka.http.scaladsl._
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling._

import akka.stream._
import akka.stream.scaladsl._

object LoadBalancingHttpClientApp extends App {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val dispatcher = system.dispatcher

  val servers = List(
    Server("localhost", 5000),
    Server("localhost", 5001))

  val done = sendRequests(5, servers)

  Await.result(done, 1.minute)
  Await.ready(Http().shutdownAllConnectionPools(), 1.second)
  Await.result(system.terminate(), 1.seconds)

  println(s"Done.")

  private def sendRequests(nrOfRequests: Int, servers: Seq[Server]): Future[Done] =
    requests(nrOfRequests)
      .via(
        LoadBalancingHttpClient().loadBalancedHostConnectionPool(servers)
      )
      .runForeach {
        case (Success(response), id) ⇒ {
          val responseAsString = Await.result(Unmarshal(response.entity).to[String], 1.second)
          println(s"(${id}) Response: ${responseAsString}")
        }
        case (Failure(cause), id) ⇒ {
          println(s"(${id}) Failed: ${cause}")
        }
      }

  private def requests(nr: Int): Source[(HttpRequest, Int), NotUsed] = {
    val reqs = (1 to nr).map { id ⇒
      HttpRequest(uri = "/twitter") → id
    }

    Source(reqs)
  }
}
