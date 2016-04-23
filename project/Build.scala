import sbt._
import sbt.Keys._
import sbt.plugins.JvmPlugin

import com.typesafe.sbt.SbtScalariform
import com.typesafe.sbt.SbtScalariform.ScalariformKeys._
import scalariform.formatter.preferences._
import scoverage.ScoverageSbtPlugin
import spray.revolver.RevolverPlugin

object Build extends Build {
  lazy val streamExperiments = Project("stream-experiments", file("."))
    .enablePlugins(BuildPlugin)
    .settings(
      name := "stream-experiments",

      libraryDependencies ++= Vector(
        Library.akkaHttp,
        Library.akkaHttpSprayJson,
        Library.akkaSlf4j,
        Library.logback,
        Library.akkaHttpTestkit  % "test",
        Library.akkaTestkit      % "test",
        Library.scalaTest        % "test"
      )
    )

}


object BuildPlugin extends AutoPlugin {
  override def requires = JvmPlugin && SbtScalariform && ScoverageSbtPlugin && RevolverPlugin
  override def trigger = allRequirements
  override def projectSettings = SbtScalariform.scalariformSettings ++ basicSettings

  private val basicSettings = Vector(
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

  private val formattingPreferences =
    FormattingPreferences()
      .setPreference(AlignParameters, false)
      .setPreference(AlignSingleLineCaseStatements, true)
      .setPreference(AlignSingleLineCaseStatements.MaxArrowIndent, 90)
      .setPreference(DoubleIndentClassDeclaration, true)
      .setPreference(RewriteArrowSymbols, true)
      .setPreference(DanglingCloseParenthesis, Preserve)
}
