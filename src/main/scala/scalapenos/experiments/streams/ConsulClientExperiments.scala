package scalapenos.experiments.streams

import scala.concurrent.duration._

import akka.actor._

import akka.http.scaladsl.client._
import akka.http.scaladsl.model._
import akka.http.scaladsl.settings._

import akka.http.scaladsl.unmarshalling._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

import akka.stream._
import akka.stream.scaladsl._

import spray.json._

object ConsulEndpoints {
  import RequestBuilding._
  import DefaultJsonProtocol._

  object catalog {
    object services {
      private val Endpoint = "/v1/catalog/services"

      def initialRequest(maxWait: Duration) = Get(consulWatchUri(Endpoint, maxWait, None))
      def nextRequest(maxWait: Duration) = (response: HttpResponse) ⇒ {
        Get(consulWatchUri(Endpoint, maxWait, consulIndex(response)))
      }

      case class Service(name: String, tags: Set[String])
      case class Services(services: Set[Service]) {
        def withTag(tag: String) = Services(services.filter(_.tags.contains(tag)))
      }

      implicit val servicesFormat = lift(new RootJsonReader[Services]() {
        def read(value: JsValue) = Services(
          value.asJsObject.fields
          .collect {
            case (name, JsArray(tags)) ⇒ Service(name, tags.map(_.convertTo[String]).toSet)
          }.toSet
        )
      })
    }
  }

  def consulIndex(response: HttpResponse) = response.headers.find(_.is("x-consul-index")).map(_.value.toLong)

  def consulWatchUri(endpoint: String, maxWait: Duration, index: Option[Long]): Uri = {
    Uri.from(
      path = endpoint,
      queryString = Some(s"wait=${maxWait.toSeconds}s&index=${index.map(_.toString).getOrElse("0")}")
    )
  }
}

object ConsulClientExperiments extends App {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val dispatcher = system.dispatcher

  import ConsulEndpoints._
  import catalog.services._

  val maxWait = 5 seconds
  val settings = ClientConnectionSettings(system).withIdleTimeout(maxWait * 1.2)

  // Challenge: write a Consul watch
  //
  //
  // The hostname below is a VPN-protected Consul server not reachable from the internet.
  // Please use your own Consul server if you want to run this code or use a different long polling API.
  val poller = LongPolling()
    .longPollingSource("consul.nl.wehkamp.prod.blaze.ps", 8500, initialRequest(maxWait), settings)(nextRequest(maxWait))
    .log("app", response ⇒ s"""Response: ${response.status} ${consulIndex(response).getOrElse("")}""")
    .via(ifChanged[HttpResponse](HttpResponse()) {
      case (r1, r2) ⇒ consulIndex(r1).forall(i1 ⇒ consulIndex(r2).exists(i2 ⇒ i2 > i1))
    })
    .mapAsync(1)(Unmarshal(_).to[Services].map(_.withTag("exposed-by-gateway")))
    .via(ifChanged[Services](Services(Set.empty[Service])) {
      case (s1, s2) ⇒ s1 != s2
    })
    .log("app", response ⇒ s"Updated: ${response.services.map(_.name)}")
    .runWith(Sink.ignore) // this is an experiment so we're only interested in the side effects, i.e. the log lines.

  // TODO:
  //  - create a ConsulEndpoint type
  //  - create a trait for Consul entities that contains the consul index so
  //    we can fuse the two mapAsync stages and the two ifChanged stages.

  // format: OFF
  // def consulWatch[T](host: String, port: Int,
  //                    endpoint: ConsulEndpoint,
  //                    connectionSettings: ClientConnectionSettings = ClientConnectionSettings(system))
  //                   (implicit m: Materializer, u: Unmarshaller[HttpResponse, T]): Source[T, NotUsed] = { // format: ON
  //   // ...
  // }

  import akka._
  private def ifChanged[T](initialValue: T)(hasChanged: (T, T) ⇒ Boolean): Flow[T, T, NotUsed] = {
    Flow[T].prepend(Source.single(initialValue)).sliding(2).collect {
      case Seq(a, b) if hasChanged(a, b) ⇒ b
    }
  }
}
