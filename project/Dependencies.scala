import sbt._

object Version {
  final val Scala       = "2.11.8"
  final val Akka        = "2.4.2"
  final val ScalaTest   = "2.2.6"
}

object Library {
  val akkaHttp          = "com.typesafe.akka"   %% "akka-http-experimental"   % Version.Akka
  val akkaHttpTestkit   = "com.typesafe.akka"   %% "akka-http-testkit"        % Version.Akka
  val akkaTestkit       = "com.typesafe.akka"   %% "akka-testkit"             % Version.Akka
  val scalaTest         = "org.scalatest"       %% "scalatest"                % Version.ScalaTest
}
