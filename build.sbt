val sparkVersion = "2.4.4"

val sparkLibs = Seq(
  "org.apache.spark" %% "spark-core" % sparkVersion % "provided",
  "org.apache.spark" %% "spark-sql" % sparkVersion % "provided"
)

lazy val commonSettings = Seq(
  organization := "xyz.graphiq",
  scalaVersion := "2.12.10",
  version := "0.1",
  libraryDependencies ++= sparkLibs
)

// Settings
val domain = "graphiq"

// For building the FAT jar
lazy val assemblySettings = Seq(
  assembly / assemblyOption := (assemblyOption in assembly).value.copy(includeScala = false),
  assembly / assemblyOutputPath := baseDirectory.value / "output" / s"${domain}-${name.value}.jar"
)

val targetDockerJarPath = "/opt/spark/jars"

// For building the docker image
lazy val dockerSettings = Seq(
  imageNames in docker := Seq(
    ImageName(s"$domain/${name.value}:latest"),
    ImageName(s"$domain/${name.value}:${version.value}"),
  ),
  buildOptions in docker := BuildOptions(
    cache = false,
    removeIntermediateContainers = BuildOptions.Remove.Always,
    pullBaseImage = BuildOptions.Pull.Always
  ),
  dockerfile in docker := {
    // The assembly task generates a fat JAR file
    val artifact: File = assembly.value
    val artifactTargetPath = s"$targetDockerJarPath/$domain-${name.value}.jar"
      new Dockerfile {
        from(s"localhost:5000/spark-runner:0.1")
      }.add(artifact, artifactTargetPath)
  }
)

// Include "provided" dependencies back to default run task 
lazy val runLocalSettings = Seq(
  // https://stackoverflow.com/questions/18838944/how-to-add-provided-dependencies-back-to-run-test-tasks-classpath/21803413#21803413
  Compile / run := Defaults
    .runTask(
      fullClasspath in Compile,
      mainClass in (Compile, run),
      runner in (Compile, run)
    )
    .evaluated
)


val root = (project in file("."))
  .enablePlugins(sbtdocker.DockerPlugin)
  .enablePlugins(AshScriptPlugin)
  .settings(
    commonSettings,
    assemblySettings,
    dockerSettings,
    runLocalSettings,
    name := "transform-movie-ratings",
    Compile / mainClass := Some("xyz.graphiq.BasicSparkJob"),
    Compile / resourceGenerators += createImporterHelmChart.taskValue
  )

// Task to create helm chart
lazy val createImporterHelmChart: Def.Initialize[Task[Seq[File]]] = Def.task {
  val chartFile = baseDirectory.value / "helm" / "Chart.yaml"
  val valuesFile = baseDirectory.value / "helm" / "values.yaml"
  
  val chartContents =
    s"""# Generated by build.sbt. Please don't manually update
       |apiVersion: v1
       |name: $domain-${name.value}
       |version: ${version.value}
       |appVersion: ${version.value}
       |description: Sample ETL Job for Medium Post
       |home: [url to post]
       |sources:
       |  - https://github.com/TomLous/medium-spark-k8s
       |maintainers: 
       |  - name: Tom Lous
       |    email: tomlous@gmail.com
       |    url: https://lous.info       
       |""".stripMargin

  val valuesContents =
    s"""# Generated by build.sbt. Please don't manually update      
       |version: ${version.value}
       |sparkVersion: ${sparkVersion}
       |image: $domain/${name.value}:${version.value}
       |imageRegistry: localhost:5000
       |jar: local://$targetDockerJarPath/$domain-${name.value}.jar
       |mainClass: ${(Compile / run / mainClass).value.getOrElse("__MAIN_CLASS__")}
       |fileDependencies: []
       |""".stripMargin

  IO.write(chartFile, chartContents)
  IO.write(valuesFile, valuesContents)
  Seq(chartFile, valuesFile)
}