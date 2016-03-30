package scalapenos.experiments.streams

import scala.concurrent.duration._
// import scala.concurrent._
import scala.util._

import akka._
import akka.actor._

import akka.http.scaladsl._
import akka.http.scaladsl.model._
import akka.http.scaladsl.settings._
// import akka.http.scaladsl.unmarshalling._

import akka.stream._
import akka.stream.scaladsl._

object LongPollingClientApp extends App {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val dispatcher = system.dispatcher

  import AkkaHttpLongPolling._

  val source = longPollingSource("consul.nl.wehkamp.prod.blaze.ps", 8500, Uri("/v1/catalog/services"), 10.seconds)

  source.runForeach(println)
}

object AkkaHttpLongPolling {
  def longPollingSource(host: String, port: Int, uri: Uri, maxWait: Duration)(implicit s: ActorSystem, fm: Materializer): Source[HttpResponse, NotUsed] = {
    import GraphDSL.Implicits._
    import s.dispatcher

    val connectionPoolSettings = ConnectionPoolSettings(s)

    Source.fromGraph(GraphDSL.create() { implicit b ⇒
      val init = Source.single(createRequest(uri, maxWait, None))
      val merge = b.add(Merge[HttpRequest](2))
      val broadcast = b.add(Broadcast[(Try[HttpResponse], HttpRequest)](2))
      val tupler = b.add(Flow[HttpRequest].map(r ⇒ (r, r)))
      val http = b.add(Http().cachedHostConnectionPool[HttpRequest](host, port, connectionPoolSettings).mapMaterializedValue(_ ⇒ NotUsed))

      val outbound = b.add(
        Flow[(Try[HttpResponse], HttpRequest)]
          .collect { case (Success(response), _) ⇒ response }
          .mapAsync(1)(response ⇒ response.entity.toStrict(5.seconds).map(strictEntity ⇒ response.copy(entity = strictEntity)))
          .withAttributes(ActorAttributes.supervisionStrategy(Supervision.resumingDecider))
      )

      val feedback = b.add(
        Flow[(Try[HttpResponse], HttpRequest)]
          .map {
            case (Success(response), _) ⇒ {
              val index = response.headers.find(_.is("x-consul-index")).map(_.value.toLong)
              println(s"Success. New index = ${index}.")
              createRequest(uri, maxWait, index)
            }
            case (Failure(cause), _) ⇒ {
              println("Failure: " + cause.getMessage)
              createRequest(uri, maxWait, None)
            }
          }
      )

      // format: OFF
      init ~> merge ~> tupler ~> http ~> broadcast ~> outbound
              merge <~ feedback       <~ broadcast
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
