package scalapenos.experiments.streams

import scala.concurrent.duration._

import akka.actor._

import akka.http.scaladsl.client._
import akka.http.scaladsl.model._
import akka.http.scaladsl.settings._

import akka.stream._
import akka.stream.scaladsl._

object LongPollingHttpClient extends App {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val dispatcher = system.dispatcher

  import Consul.Catalog.Services._

  val maxWait = 5 seconds
  val settings = ClientConnectionSettings(system).withIdleTimeout(maxWait * 1.2)

  // The hostname below is a VPN-protected Consul server not reachable from the internet.
  // Please use your own Consul server if you want to run this code or use a different long polling API.
  // format: OFF
  val source = LongPolling().longPollingSource("consul.nl.wehkamp.prod.blaze.ps", 8500,
                                               initialRequest(maxWait),
                                               settings)(nextRequest(maxWait)) // format: ON

  val logged = source.log("app", out ⇒ s"Received response: ${out.status}")

  logged.runWith(Sink.ignore) // this is an experiment so we're only interested in the side effects, i.e. the log lines.
}

object Consul {
  import RequestBuilding._

  object Catalog {
    object Services {
      private val Endpoint = "/v1/catalog/services"

      def initialRequest(maxWait: Duration) = Get(uri(Endpoint, maxWait, None))
      def nextRequest(maxWait: Duration) = (response: HttpResponse) ⇒ {
        val index = response.headers.find(_.is("x-consul-index")).map(_.value.toLong)
        Get(uri(Endpoint, maxWait, index))
      }
    }
  }

  private def uri(endpoint: String, maxWait: Duration, index: Option[Long]): Uri = {
    Uri.from(
      path = endpoint,
      queryString = Some(s"wait=${maxWait.toSeconds}s&index=${index.map(_.toString).getOrElse("0")}")
    )
  }
}
