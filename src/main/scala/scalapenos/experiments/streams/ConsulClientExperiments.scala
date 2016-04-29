package scalapenos.experiments.streams

import scala.concurrent._
import scala.concurrent.duration._

import akka._
import akka.actor._

import akka.http.scaladsl.client._
import akka.http.scaladsl.model._
import akka.http.scaladsl.settings._

import akka.http.scaladsl.unmarshalling._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

import akka.stream._
import akka.stream.scaladsl._

import spray.json._

object ConsulEntities {
  case class Service(name: String, tags: Set[String])
  case class Services(services: Set[Service]) {
    def withTag(tag: String) = Services(services.filter(_.tags.contains(tag)))
  }

  object Services extends DefaultJsonProtocol {
    implicit val reader = lift(new RootJsonReader[Services]() {
      def read(value: JsValue) = Services(
        value.asJsObject.fields
        .collect {
          case (name, JsArray(tags)) ⇒ Service(name, tags.map(_.convertTo[String]).toSet)
        }.toSet
      )
    })
  }

  case class Node(host: String, port: Int) {
    require(host.nonEmpty, "A node should have non-empty host name or ip/address!")
    require(port > 0, "A node should have a positive port number!")

    override val toString = s"${host}:${port}"
  }
  object Node extends DefaultJsonProtocol {
    implicit val reader = lift(new RootJsonReader[Node]() {
      def read(json: JsValue): Node = {
        json.asJsObject.getFields("Service") match {
          case Seq(service: JsObject) ⇒
            service.getFields("Address", "Port") match {
              case Seq(JsString(address), JsNumber(port)) ⇒ Node(address, port.intValue)
              case _                                      ⇒ deserializationError("Invalid json node")
            }
          case _ ⇒ deserializationError("Invalid json node")
        }
      }
    })
  }

  case class KV(key: String, value: Option[String])
  object KV extends DefaultJsonProtocol {
    implicit val reader = lift(new RootJsonReader[KV]() {
      def read(json: JsValue): KV = {
        json.asJsObject.getFields("Key", "Value") match {
          case Seq(JsString(key), JsString(value)) ⇒ KV(key, base64.Decode(value).right.toOption.map(new String(_, java.nio.charset.StandardCharsets.UTF_8)))
          case Seq(JsString(key), JsNull)          ⇒ KV(key, None)
          case other                               ⇒ deserializationError("Invalid json node: " + other)
        }
      }
    })
  }
}

object ConsulUtils {
  def consulIndex(response: HttpResponse) = response.headers.find(_.is("x-consul-index")).map(_.value.toLong)
}

object ConsulEndpoints {
  import ConsulEntities._
  import ConsulUtils._
  import RequestBuilding._

  abstract class ConsulEndpoint[T](val baseUri: Uri) {
    def initialRequest(implicit maxWait: Duration): HttpRequest = Get(uri(maxWait, None))
    def nextRequest(implicit maxWait: Duration): HttpResponse ⇒ HttpRequest = (response: HttpResponse) ⇒ {
      Get(uri(maxWait, consulIndex(response)))
    }

    private def uri(maxWait: Duration, index: Option[Long]): Uri = {
      import Uri._
      baseUri.withQuery(
        Query.Cons("index", index.map(_.toString).getOrElse("0"), Query.Cons("wait", s"${maxWait.toSeconds}s", baseUri.query()))
      )
    }
  }

  object AllServices extends ConsulEndpoint[Services](Uri("/v1/catalog/services"))
  case class ServiceNodes(name: String) extends ConsulEndpoint[List[Node]](Uri(s"/v1/health/service/${name}?passing"))
  case class KVs(path: String) extends ConsulEndpoint[List[KV]](Uri(s"/v1/kv/$path/?recurse"))
}

object StreamUtils {
  def ifChanged[T](hasChanged: (T, T) ⇒ Boolean = (a: T, b: T) ⇒ a != b): Flow[T, T, NotUsed] = {
    Flow[T]
      .map(Option(_))
      .prepend(Source.single(Option.empty[T]))
      .sliding(2)
      .collect {
        case Seq(None, Some(b))                        ⇒ b
        case Seq(Some(a), Some(b)) if hasChanged(a, b) ⇒ b
      }
  }
}

object ConsulClient {
  import ConsulEndpoints._
  import ConsulUtils._
  import StreamUtils._

  // TODO: handle non-200 status codes!!

  // format: OFF
  def consulWatch[T](host: String, port: Int,
                     endpoint: ConsulEndpoint[T],
                     connectionSettings: ClientConnectionSettings)
                    (implicit system: ActorSystem,
                              materializer: Materializer,
                              unmarshaller: FromResponseUnmarshaller[T],
                              ec: ExecutionContext,
                              maxWait: Duration): Source[T, NotUsed] = { // format: ON
    LongPolling()
      .longPollingSource(host, port, endpoint.initialRequest, connectionSettings)(endpoint.nextRequest)
      // log the response so we know something is happening
      .log("consul", response ⇒ s"""Response: ${response.status} ${consulIndex(response).getOrElse("")}""")
      // unmarshal to our target type but retain the consul index
      .mapAsync(1)(response ⇒
        Unmarshal(response)
          .to[T]
          .map(t ⇒ ConsulEntity(consulIndex(response).getOrElse(0L), t))
          .recover {
            case e: Exception ⇒ e.printStackTrace; throw e
          }
      )
      // Recover from unmarshalling errors.
      // TODO: log errors!
      .withAttributes(ActorAttributes.supervisionStrategy(Supervision.resumingDecider))
      // we are only interested in responses with an index header value greater than the previous one
      // and an unmarshalled entity that has changed
      .via(ifChanged((e1, e2) ⇒
        e1.index > e2.index && e1.value != e2.value
      ))
      // Get rid of the ConsulEntity wrapper
      .map(_.value)
      // Name this flow
      .named(s"consul-watch-${host}:${port}-${endpoint.baseUri}")
  }

  private case class ConsulEntity[T](index: Long, value: T)
}

object ConsulClientExperiments extends App {
  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val dispatcher = system.dispatcher

  import ConsulEntities._
  import ConsulEndpoints._
  import ConsulClient._

  implicit val maxWait = 5 seconds
  val settings = ClientConnectionSettings(system).withIdleTimeout(maxWait * 1.2)

  // The hostname below is a VPN-protected Consul server not reachable from the internet.
  // Please use your own Consul server if you want to run this code!
  val host = "consul.nl.wehkamp.prod.blaze.ps"
  val port = 8500

  // consulWatch[Services](host, port, AllServices, settings)
  //   .map(_.withTag("exposed-by-gateway")) // Please use your own tag or remove this line
  //   .log("app", response ⇒ s"Updated: ${response.services.map(_.name)}")
  //   .runWith(Sink.ignore) // this is an experiment so we're only interested in the side effects, i.e. the log lines.

  // consulWatch[List[Node]](host, port, ServiceNodes("blaze-canary-service"), settings)
  //   .log("app", nodes ⇒ s"Updated: ${nodes}")
  //   .runWith(Sink.ignore) // this is an experiment so we're only interested in the side effects, i.e. the log lines.

  // consulWatch[List[KV]](host, port, KVs("services/blaze-canary-service"), settings)
  //   .log("app", kvs ⇒ s"Updated: ${kvs}")
  //   .runWith(Sink.ignore) // this is an experiment so we're only interested in the side effects, i.e. the log lines.

  case class ServiceInfo(nodes: Set[Node], kvs: Set[KV])

  consulWatch(host, port, ServiceNodes("blaze-canary-service"), settings)
    .zipWith(consulWatch(host, port, KVs("services/blaze-canary-service"), settings))((nodes, kvs) ⇒ ServiceInfo(nodes.toSet, kvs.toSet))
    .log("app", nodes ⇒ s"Updated: ${nodes}")
    .runWith(Sink.ignore) // this is an experiment so we're only interested in the side effects, i.e. the log lines.
}
