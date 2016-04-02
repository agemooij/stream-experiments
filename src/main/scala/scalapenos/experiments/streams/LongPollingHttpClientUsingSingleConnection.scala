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

  source.runForeach(r ⇒ println(r.copy(entity = HttpEntity.Empty)))
}

object LongPollingHttpClientUsingSingleConnection {
  def longPollingSource(host: String, port: Int, uri: Uri, maxWait: Duration)(implicit s: ActorSystem, fm: Materializer): Source[HttpResponse, NotUsed] = {
    Source.fromGraph(GraphDSL.create() { implicit b ⇒
      import GraphDSL.Implicits._
      import s.dispatcher

      val initSource: Source[HttpRequest, NotUsed] = Source.single(createRequest(uri, maxWait, None))
      val httpFlow: Flow[HttpRequest, HttpResponse, NotUsed] =
        Flow[HttpRequest]
          .flatMapConcat(request ⇒ Source.single(request).via(Http().outgoingConnection(host, port)))
          .mapMaterializedValue(_ ⇒ NotUsed)

      val outboundResponse: Flow[HttpResponse, HttpResponse, NotUsed] =
        Flow[HttpResponse]
          .mapAsync(1)(response ⇒ response.entity.toStrict(5.seconds).map(strictEntity ⇒ response.copy(entity = strictEntity)))
          .withAttributes(ActorAttributes.supervisionStrategy(Supervision.resumingDecider))

      val feedbackResponse: Flow[HttpResponse, HttpRequest, NotUsed] =
        Flow[HttpResponse]
          .map { response ⇒
            val index = response.headers.find(_.is("x-consul-index")).map(_.value.toLong)
            println(s"Success. New index = ${index}.")
            createRequest(uri, maxWait, index)
          }

      val init = b.add(initSource)
      val http = b.add(httpFlow)
      val merge = b.add(Merge[HttpRequest](2))
      val broadcast = b.add(Broadcast[HttpResponse](2))
      val outbound = b.add(outboundResponse)
      val feedback = b.add(feedbackResponse)

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
