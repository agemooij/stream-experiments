package scalapenos.experiments.streams

import scala.concurrent.duration._
import scala.util._

import akka._
import akka.actor._

import akka.http.scaladsl._
import akka.http.scaladsl.model._
import akka.http.scaladsl.settings._

import akka.stream._
import akka.stream.scaladsl._

class LongPollingExt(system: ActorSystem) extends Extension {
  implicit val s = system

  import Http._

  //
  //  returns:
  //    a Source[HttpResponse, LongPoller]
  //    with the materialized LongPoller value to safely shutdown the long
  //    polling operation and any connections or pools/actors used internally
  //
  // format: OFF
  def longPollingSource(host: String, port: Int,
                        initialRequest: HttpRequest,
                        connectionSettings: ClientConnectionSettings = ClientConnectionSettings(system))
                       (nextRequest: HttpResponse ⇒ HttpRequest = _ => initialRequest)
                       (implicit m: Materializer): Source[HttpResponse, NotUsed] = { // format: ON
    Source.fromGraph(GraphDSL.create() { implicit b ⇒
      import GraphDSL.Implicits._
      import s.dispatcher

      val initSource: Source[HttpRequest, NotUsed] =
        Source.single(initialRequest)

      val httpFlow: Flow[HttpRequest, Try[HttpResponse], NotUsed] =
        Flow[HttpRequest]
          .log("long-poller", out ⇒ s"Sending request: ${out.uri}")
          .via(singleConnectionCustomPool(host, port, connectionSettings))
          .mapMaterializedValue(_ ⇒ NotUsed) // TODO: use the materialized value to allow shutdown

      val outboundResponsesFlow: Flow[Try[HttpResponse], HttpResponse, NotUsed] =
        Flow[Try[HttpResponse]] // TODO: add size limit
          .collect { case Success(response) ⇒ response }
          .mapAsync(1)(response ⇒ response.entity.toStrict(5.seconds).map(strictEntity ⇒ response.copy(entity = strictEntity)))
          .withAttributes(ActorAttributes.supervisionStrategy(Supervision.resumingDecider)) // TODO: turn into log-and-resume

      val feedbackResponsesFlow: Flow[Try[HttpResponse], HttpRequest, NotUsed] =
        Flow[Try[HttpResponse]]
          .map {
            case Success(response) ⇒ nextRequest(response)
            case Failure(cause)    ⇒ initialRequest // TODO: log something
          }

      val init = b.add(initSource)
      val http = b.add(httpFlow)
      val merge = b.add(Merge[HttpRequest](2))
      val broadcast = b.add(Broadcast[Try[HttpResponse]](2))
      val outbound = b.add(outboundResponsesFlow)
      val feedback = b.add(feedbackResponsesFlow)

      // format: OFF
      init ~> merge ~> http     ~> broadcast ~> outbound
              merge <~ feedback <~ broadcast
      // format: ON

      SourceShape(outbound.out)
    }).withAttributes(ActorAttributes.supervisionStrategy(Supervision.restartingDecider))
  }

  private def singleConnectionCustomPool(host: String, port: Int, //format: OFF
                                         connectionSettings: ClientConnectionSettings)
                                        (implicit m: Materializer): Flow[HttpRequest, Try[HttpResponse], HostConnectionPool] = { // format: ON
    val poolSettings = ConnectionPoolSettings(system)
      .withMaxConnections(1)
      .withPipeliningLimit(1)
      .withMaxRetries(0)
      .withConnectionSettings(connectionSettings)

    Flow[HttpRequest]
      .map(request ⇒ (request, 42))
      .viaMat(Http().newHostConnectionPool(host, port, poolSettings))(Keep.right)
      .map { case (responseTry, _) ⇒ responseTry }
  }
}

object LongPolling extends ExtensionId[LongPollingExt] with ExtensionIdProvider {
  def lookup() = LongPolling
  def apply()(implicit system: ActorSystem): LongPollingExt = super.apply(system)
  def createExtension(system: ExtendedActorSystem) = new LongPollingExt(system)
}
