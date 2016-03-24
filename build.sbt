lazy val streamExperiments = project
  .copy(id = "stream-experiments")
  .in(file("."))
  .settings(
    name := "stream-experiments",

    libraryDependencies ++= Vector(
      Library.akkaHttp,
      Library.akkaHttpTestkit      % "test",
      Library.akkaTestkit          % "test",
      Library.scalaTest            % "test"
    )
  )
