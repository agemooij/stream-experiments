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

import akka.stream._
import akka.stream.scaladsl._

object LoadBalancedClientApp extends App {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val dispatcher = system.dispatcher

  Await.result(responseStreamFuture(5000), 1.minute)
  Await.ready(Http().shutdownAllConnectionPools(), 1.second)
  Await.result(system.terminate(), 1.seconds)

  println(s"Done.")

  private def responseStreamFuture(port: Int): Future[Done] =
    requests()
      .via(poolClientFlow(port))
      .runForeach {
        case (response, id) ⇒ {
          val responseAsString = Await.result(Unmarshal(response.get.entity).to[String], 1.second)
          println(s"${id}: ${responseAsString}")
        }
      }

  private def requests(): Source[(HttpRequest, Int), NotUsed] = {
    Source.single(HttpRequest(uri = "/twitter") → 42)
  }

  private def poolClientFlow(port: Int): Flow[(HttpRequest, Int), (Try[HttpResponse], Int), Http.HostConnectionPool] =
    Http().cachedHostConnectionPool[Int](host = "localhost", port = port)
}
