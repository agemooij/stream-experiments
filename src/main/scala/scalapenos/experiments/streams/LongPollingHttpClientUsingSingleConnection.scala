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

object LongPollingHttpClientUsingSingleConnectionApp extends App {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val dispatcher = system.dispatcher

  import LongPollingHttpClientUsingSingleConnection._

  val source = longPollingSource("consul.nl.wehkamp.prod.blaze.ps", 8500, Uri("/v1/catalog/services"), 5.seconds)

  source.runWith(Sink.ignore)
}

object LongPollingHttpClientUsingSingleConnection {
  def longPollingSource(host: String, port: Int, uri: Uri, maxWait: Duration)(implicit s: ActorSystem, fm: Materializer): Source[HttpResponse, NotUsed] = {
    Source.fromGraph(GraphDSL.create() { implicit b ⇒
      import GraphDSL.Implicits._
      import s._

      val settings = ClientConnectionSettings(s).withIdleTimeout(maxWait * 1.2)

      val initSource: Source[HttpRequest, NotUsed] = Source.single(createRequest(uri, maxWait, None)).log("outer 0", out ⇒ s"Sending request...")
      val httpFlow: Flow[HttpRequest, HttpResponse, NotUsed] =
        Flow[HttpRequest]
          .log("outer 1", out ⇒ s"Sending request...")
          .flatMapConcat(request ⇒
            Source.single(request)
              .log("inner 1", out ⇒ s"Sending request to new connection...")
              .via(Http().outgoingConnection(host, port, settings = settings).log("inner 2", out ⇒ s"Received response: ${out.status}"))
          )
          .mapMaterializedValue(_ ⇒ NotUsed)
          .log("outer 2", out ⇒ s"Received response: ${out.status}")

      val outboundResponsesFlow: Flow[HttpResponse, HttpResponse, NotUsed] =
        Flow[HttpResponse] // TODO: add size limit
          .mapAsync(1)(response ⇒ response.entity.toStrict(5.seconds).map(strictEntity ⇒ response.copy(entity = strictEntity)))
          .withAttributes(ActorAttributes.supervisionStrategy(Supervision.resumingDecider)) // TODO: turn into log-and-resume

      val feedbackResponsesFlow: Flow[HttpResponse, HttpRequest, NotUsed] =
        Flow[HttpResponse]
          .map { response ⇒
            log.debug(s"Success. Response: ${response.copy(entity = HttpEntity.Empty)}.")
            val index = response.headers.find(_.is("x-consul-index")).map(_.value.toLong)
            log.debug(s"New index: ${index}.")
            createRequest(uri, maxWait, index)
          }

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

  private def createRequest(baseUri: Uri, maxWait: Duration, index: Option[Long]): HttpRequest = {
    HttpRequest(
      uri = baseUri.copy(
        rawQueryString = Some(s"wait=${maxWait.toSeconds}s&index=${index.map(_.toString).getOrElse("0")}")
      )
    )
  }
}
