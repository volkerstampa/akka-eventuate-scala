import com.typesafe.sbt.SbtMultiJvm
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm

val akkaVersion = "2.4.2"

val project = Project(
  id = "akka-eventuate-scala",
  base = file("."),
  settings = Defaults.coreDefaultSettings ++ SbtMultiJvm.multiJvmSettings ++ Seq(
    name := "akka-eventuate-scala",
    version := "0.6-SNAPSHOT",
    scalaVersion := "2.11.7",
    resolvers += "Eventuate Releases" at "https://dl.bintray.com/rbmhtechnology/maven",
    libraryDependencies ++= Seq(
      "com.rbmhtechnology" %% "eventuate-core" % "0.6",
      "com.rbmhtechnology" %% "eventuate-log-leveldb" % "0.6",
      "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
      "ch.qos.logback" % "logback-classic" % "1.1.6",
      "com.typesafe.akka" %% "akka-multi-node-testkit" % akkaVersion % Test,
      "org.scalatest" %% "scalatest" % "2.2.6" % Test),
    // make sure that MultiJvm test are compiled by the default test compilation
    compile in MultiJvm <<= (compile in MultiJvm) triggeredBy (compile in Test),
    // disable parallel tests
    parallelExecution in Test := false,
    fork in run := true,
    Keys.connectInput in run := true,
    mainClass in (Compile, run) := Some("sample.eventuate.OrderBot"),
    // make sure that MultiJvm tests are executed by the default test target, 
    // and combine the results from ordinary test and multi-jvm tests
    executeTests in Test <<= (executeTests in Test, executeTests in MultiJvm) map {
      case (testResults, multiNodeResults)  =>
        val overall =
          if (testResults.overall.id < multiNodeResults.overall.id)
            multiNodeResults.overall
          else
            testResults.overall
          Tests.Output(overall,
            testResults.events ++ multiNodeResults.events,
            testResults.summaries ++ multiNodeResults.summaries)
    }
  )
) configs MultiJvm


fork in run := true