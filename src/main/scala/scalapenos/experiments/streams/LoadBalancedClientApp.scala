package scalapenos.experiments.streams

import java.io.File

import scala.concurrent.duration._
import scala.concurrent._
import scala.util._

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

  val NoLimit = Long.MaxValue

  def poolClientFlow(port: Int) = Http().cachedHostConnectionPool[Int](host = "localhost", port = port)

  def responseFuture(port: Int): Future[(Try[HttpResponse], Int)] =
    Source.single(HttpRequest(uri = "/twitter") → 42)
      .via(poolClientFlow(port))
      .runWith(Sink.head)

  val bytesWritten = responseFuture(5000) flatMap { r ⇒
    val (response, _) = r
    Unmarshal(response.get.entity).to[String]
  }

  val result = Await.result(bytesWritten, atMost = 1.minute)

  println(s"Done.")
  println(s"Result: $result")

  Await.ready(Http().shutdownAllConnectionPools(), 1.second)
  Await.result(system.terminate(), 1.seconds)
}
