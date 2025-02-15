name := "javasrc2cpg"

scalaVersion       := "2.13.8"
crossScalaVersions := Seq("2.13.8", "3.2.1")

dependsOn(Projects.dataflowengineoss, Projects.x2cpg % "compile->compile;test->test")

libraryDependencies ++= Seq(
  "io.shiftleft"            %% "codepropertygraph"             % Versions.cpg,
  "org.apache.logging.log4j" % "log4j-slf4j-impl"              % Versions.log4j     % Runtime,
  "com.github.javaparser"    % "javaparser-symbol-solver-core" % "3.24.3-SNAPSHOT",
  //"com.github.javaparser"    % "javaparser-symbol-solver-core" % "3.24.2",
  //"io.joern"                 % "javaparser-symbol-solver-core" % "3.24.3-SL3", // custom build of our fork, sources at https://github.com/mpollmeier/javaparser
  "org.gradle"        % "gradle-tooling-api" % Versions.gradleTooling,
  "org.scalatest"    %% "scalatest"          % Versions.scalatest % Test,
  "org.projectlombok" % "lombok"             % "1.18.24",
  "org.scala-lang.modules" %% "scala-parallel-collections" % "1.0.4"
)

scalacOptions ++= Seq(
  "-deprecation", // Emit warning and location for usages of deprecated APIs.
  "-Yrepl-class-based"
)

enablePlugins(JavaAppPackaging, LauncherJarPlugin)
trapExit                      := false
Global / onChangedBuildSource := ReloadOnSourceChanges
