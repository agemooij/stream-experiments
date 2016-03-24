package scalapenos.experiments.streams

import scala.util._

import akka._
import akka.actor._
import akka.event._

import akka.http.scaladsl._
import akka.http.scaladsl.model._
import akka.http.scaladsl.settings._

import akka.stream._
import akka.stream.scaladsl._

import com.typesafe.config._

/**  */
case class Server(host: String, port: Int)

/**
 *
 */
class HttpClientExt(private val config: Config)(implicit val system: ActorSystem) extends Extension {

  /**
   *
   */
  def loadBalancedHostConnectionPool[T](
    servers: Seq[Server],
    settings: ConnectionPoolSettings = ConnectionPoolSettings(system),
    log: LoggingAdapter = system.log
  )(implicit fm: Materializer): Flow[(HttpRequest, T), (Try[HttpResponse], T), NotUsed] = {
    val workers = servers.map { server ⇒
      Flow[(HttpRequest, T)]
        .log("load-balancer", out ⇒ s"${out._2}: Sending request to ${server.host}:${server.port}")(log)
        .via(
          Http().cachedHostConnectionPool[T](
            host = server.host,
            port = server.port,
            settings = settings,
            log = log
          ).mapMaterializedValue(_ ⇒ NotUsed)
        )
    }

    balancer[(HttpRequest, T), (Try[HttpResponse], T)](workers)
  }

  private def balancer[In, Out](workers: Seq[Flow[In, Out, Any]]): Flow[In, Out, NotUsed] = {
    import GraphDSL.Implicits._

    Flow.fromGraph(GraphDSL.create() { implicit b ⇒
      val balancer = b.add(Balance[In](workers.size, waitForAllDownstreams = true))
      val merge = b.add(Merge[Out](workers.size))

      workers.foreach { worker ⇒
        balancer ~> worker ~> merge
      }

      FlowShape(balancer.in, merge.out)
    })
  }
}

object HttpClient extends ExtensionId[HttpClientExt] with ExtensionIdProvider {
  def lookup() = HttpClient
  def apply()(implicit system: ActorSystem): HttpClientExt = super.apply(system)
  def createExtension(system: ExtendedActorSystem): HttpClientExt =
    new HttpClientExt(system.settings.config getConfig "akka.http")(system)

}
