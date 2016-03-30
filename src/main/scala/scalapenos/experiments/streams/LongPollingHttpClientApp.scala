package scalapenos.experiments.streams

import scala.concurrent.duration._

import akka.actor._
import akka.http.scaladsl.model._
import akka.stream._

import LongPollingHttpClientUsingHostConnectionPool._

object LongPollingHttpClientApp extends App {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val dispatcher = system.dispatcher

  val source = longPollingSource("consul.nl.wehkamp.prod.blaze.ps", 8500, Uri("/v1/catalog/services"), 10.seconds)

  source.runForeach(println)
}
