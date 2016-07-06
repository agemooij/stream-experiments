import sbt._

object Version {
  final val Scala       = "2.11.8"
  final val Akka        = "2.4.7"
  final val ScalaTest   = "2.2.6"
}

object Library {
  val akkaHttp           = "com.typesafe.akka"  %%  "akka-http-experimental"             %  Version.Akka
  val akkaSlf4j          = "com.typesafe.akka"  %%  "akka-slf4j"                         %  Version.Akka
  val akkaHttpTestkit    = "com.typesafe.akka"  %%  "akka-http-testkit"                  %  Version.Akka
  val akkaHttpSprayJson  = "com.typesafe.akka"  %%  "akka-http-spray-json-experimental"  %  Version.Akka
  val akkaTestkit        = "com.typesafe.akka"  %%  "akka-testkit"                       %  Version.Akka
  val scalaTest          = "org.scalatest"      %%  "scalatest"                          %  Version.ScalaTest

  val base64             = "me.lessis"          %%  "base64"                             % "0.2.0"
  val logback            = "ch.qos.logback"      %  "logback-classic"                    % "1.1.7"
}
