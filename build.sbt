import sbt._
import sbt.Keys._

import com.typesafe.sbt.SbtScalariform.ScalariformKeys._


lazy val streamExperiments = project
  .copy(id = "stream-experiments")
  .in(file("."))
  .settings(
    libraryDependencies ++= Vector(
      Library.akkaHttp,
      Library.akkaHttpSprayJson,
      Library.akkaSlf4j,
      Library.logback,
      Library.base64,
      Library.akkaHttpTestkit  % "test",
      Library.akkaTestkit      % "test",
      Library.scalaTest        % "test"
    ),

    organization := "com.scalapenos",
    scalaVersion := Version.Scala,
    crossScalaVersions := Vector(scalaVersion.value),

    licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0")),
    scalacOptions ++= Vector(
      "-unchecked",
      "-deprecation",
      "-language:_",
      "-target:jvm-1.8",
      "-encoding", "UTF-8",
      "-Xlog-reflective-calls",
      "-Ywarn-unused",
      "-Ywarn-unused-import"
    ),

    preferences in Compile := formattingPreferences,
    preferences in Test    := formattingPreferences
  )


lazy val formattingPreferences = {
  import scalariform.formatter.preferences._

  FormattingPreferences()
    .setPreference(AlignParameters, false)
    .setPreference(AlignSingleLineCaseStatements, true)
    .setPreference(AlignSingleLineCaseStatements.MaxArrowIndent, 90)
    .setPreference(DoubleIndentClassDeclaration, true)
    .setPreference(RewriteArrowSymbols, true)
    .setPreference(DanglingCloseParenthesis, Preserve)
}
