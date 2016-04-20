package scalapenos.experiments.streams

import scala.concurrent.duration._

import akka._
import akka.actor._

import akka.http.scaladsl._
import akka.http.scaladsl.client._
import akka.http.scaladsl.model._
import akka.http.scaladsl.settings._

import akka.stream._
import akka.stream.scaladsl._

object LongPollingHttpClientUsingSingleConnection2App extends App {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val dispatcher = system.dispatcher

  import Consul.Catalog.Services._

  val maxWait = 5 seconds
  val source = LongPolling().longPollingSource("consul.nl.wehkamp.prod.blaze.ps", 8500, initialRequest(maxWait), nextRequest(maxWait))

  source.runWith(Sink.ignore)
}

class LongPollingExt(system: ActorSystem) extends Extension {
  implicit val s = system

  //
  //  returns:
  //    a Source[HttpResponse, LongPoller]
  //    with the materialized LongPoller value to safely shutdown the long polling operation
  //
  // format: OFF
  def longPollingSource(host: String, port: Int,
                        initialRequest: HttpRequest,
                        nextRequest: HttpResponse ⇒ HttpRequest)
                       (implicit m: Materializer): Source[HttpResponse, NotUsed] = { // format: ON
    Source.fromGraph(GraphDSL.create() { implicit b ⇒
      import GraphDSL.Implicits._
      import s._

      val settings = ClientConnectionSettings(s) //.withIdleTimeout(maxWait * 1.2)

      val initSource: Source[HttpRequest, NotUsed] =
        Source.single(initialRequest).log("outer 0", out ⇒ s"Sending request...")

      val httpFlow: Flow[HttpRequest, HttpResponse, NotUsed] =
        Flow[HttpRequest]
          .log("long-poller", out ⇒ s"Sending request: ${out.uri}")
          .via(Http().outgoingConnection(host, port, settings = settings))
          .mapMaterializedValue(_ ⇒ NotUsed)
          .log("long-poller", out ⇒ s"Received response: ${out.status}")

      val outboundResponsesFlow: Flow[HttpResponse, HttpResponse, NotUsed] =
        Flow[HttpResponse] // TODO: add size limit
          .mapAsync(1)(response ⇒ response.entity.toStrict(5.seconds).map(strictEntity ⇒ response.copy(entity = strictEntity)))
          .withAttributes(ActorAttributes.supervisionStrategy(Supervision.resumingDecider)) // TODO: turn into log-and-resume

      val feedbackResponsesFlow: Flow[HttpResponse, HttpRequest, NotUsed] =
        Flow[HttpResponse]
          .map(nextRequest(_))

      val init = b.add(initSource)
      val http = b.add(httpFlow)
      val merge = b.add(Merge[HttpRequest](2))
      val broadcast = b.add(Broadcast[HttpResponse](2))
      val outbound = b.add(outboundResponsesFlow)
      val feedback = b.add(feedbackResponsesFlow)

      // format: OFF
      init ~> merge ~> http     ~> broadcast ~> outbound
              merge <~ feedback <~ broadcast
      // format: ON

      SourceShape(outbound.out)
    })
  }
}

object LongPolling extends ExtensionId[LongPollingExt] with ExtensionIdProvider {
  def lookup() = LongPolling
  def apply()(implicit system: ActorSystem): LongPollingExt = super.apply(system)
  def createExtension(system: ExtendedActorSystem) = new LongPollingExt(system)

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
