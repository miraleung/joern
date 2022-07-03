name := "joern-cli"

dependsOn(Projects.console, Projects.console % "test->test", Projects.c2cpg, Projects.dataflowengineoss, Projects.x2cpg)

// Prevent scala-xml binary-incompatible compile-time errors. Sample error:
/*
(joerncli / update) found version conflict(s) in library dependencies; some are suspected to be binary incompatible:

	* org.scala-lang.modules:scala-xml_2.13:2.1.0 (early-semver) is selected over {2.0.1, 1.3.0}
	    +- io.shiftleft:overflowdb-formats_2.13:1.136         (depends on 2.1.0)
	    +- net.liftweb:lift-json_2.13:3.5.0                   (depends on 1.3.0)
	    +- com.lihaoyi:ammonite-interp_2.13.8:2.5.4           (depends on 2.0.1)
	    +- com.lihaoyi:ammonite-compiler_2.13.8:2.5.4         (depends on 2.0.1)
*/

ThisBuild / evictionErrorLevel := Level.Warn

libraryDependencies ++= Seq(
  "io.shiftleft"            %% "codepropertygraph" % Versions.cpg,
  "com.lihaoyi"             %% "requests"          % "0.7.0",
  "com.github.scopt"        %% "scopt"             % "4.0.1",
  "com.github.pathikrit"    %% "better-files"      % "3.9.1",
  "io.circe"                %% "circe-generic"     % "0.14.2",
  "org.reflections"          % "reflections"       % "0.10.2",
  "org.apache.logging.log4j" % "log4j-slf4j-impl"  % Versions.log4j     % Runtime,
  "org.scalatest"           %% "scalatest"         % Versions.scalatest % Test,
  "net.liftweb"             %% "lift-json"         % "3.5+",
)

enablePlugins(UniversalPlugin)
enablePlugins(JavaAppPackaging)
//wildcard import from staged `lib` dir, for simplicity and also to avoid `line too long` error on windows
scriptClasspath := Seq("*")

topLevelDirectory := Some(packageName.value)

Compile / packageDoc / mappings := Seq()

def frontendMappings(frontendName: String, stagedProject: File): Seq[(File, String)] = {
  NativePackagerHelper.contentOf(stagedProject).map { case (file, name) =>
    file -> s"frontends/$frontendName/$name"
  }
}

lazy val x2cpg       = project.in(file("frontends/x2cpg"))
lazy val kotlin2cpg  = project.in(file("frontends/kotlin2cpg"))
lazy val javasrc2cpg = project.in(file("frontends/javasrc2cpg"))
lazy val pysrc2cpg   = project.in(file("frontends/pysrc2cpg"))
lazy val php2cpg     = project.in(file("frontends/php2cpg"))
lazy val jimple2cpg  = project.in(file("frontends/jimple2cpg"))
lazy val jssrc2cpg   = project.in(file("frontends/jssrc2cpg"))
lazy val js2cpg = project
  .in(file("frontends/js2cpg"))
  .enablePlugins(JavaAppPackaging)
  .settings(
    libraryDependencies += "io.shiftleft" %% "js2cpg" % Versions.js2cpg,
    Compile / mainClass                   := Some("io.shiftleft.js2cpg.core.Js2CpgMain")
  )

Universal / mappings ++= frontendMappings("kotlin2cpg", (kotlin2cpg / stage).value)
Universal / mappings ++= frontendMappings("javasrc2cpg", (javasrc2cpg / stage).value)
Universal / mappings ++= frontendMappings("c2cpg", (Projects.c2cpg / stage).value)
Universal / mappings ++= frontendMappings("ghidra2cpg", (Projects.ghidra2cpg / stage).value)
Universal / mappings ++= frontendMappings("jssrc2cpg", (jssrc2cpg / stage).value)
Universal / mappings ++= frontendMappings("js2cpg", (js2cpg / stage).value)
Universal / mappings ++= frontendMappings("jimple2cpg", (jimple2cpg / stage).value)
Universal / mappings ++= frontendMappings("pysrc2cpg", (pysrc2cpg / stage).value)
Universal / mappings ++= frontendMappings("phpcpg", (php2cpg / stage).value)

lazy val cpgVersionFile = taskKey[File]("persist cpg version in file (e.g. for schema-extender)")
cpgVersionFile := {
  val ret = target.value / "cpg-version"
  better.files
    .File(ret.getPath)
    .createIfNotExists(createParents = true)
    .writeText(Versions.cpg)
  ret
}
Universal / mappings += cpgVersionFile.value -> "schema-extender/cpg-version"

lazy val generateScaladocs = taskKey[File]("generate scaladocs from combined project sources")
generateScaladocs := {
  import better.files._
  import java.io.{File => JFile, PrintWriter}
  import sbt.internal.inc.AnalyzingCompiler
  import sbt.internal.util.Attributed.data
  import net.lingala.zip4j.ZipFile
  import sbt.internal.CommandStrings.ExportStream

  val updateReport = updateClassifiers.value
  val label        = "Joern API documentation"
  val s            = streams.value
  val out          = target.value / "api"
  val fiOpts       = (Compile / doc / fileInputOptions).value

  val sOpts = Seq("-language:implicitConversions", "-doc-root-content", "api-doc-root.txt", "-implicits")

  val xapis   = apiMappings.value
  val options = sOpts ++ Opts.doc.externalAPI(xapis)
  val cp      = data((Compile / dependencyClasspath).value).toList

  val inputFilesRelativeDir = target.value + "/inputFiles"
  val inputFiles            = File(inputFilesRelativeDir)
  if (inputFiles.exists) inputFiles.delete()
  inputFiles.createDirectory()

  /* extract sources-jar dependencies */
  List("codepropertygraph", "semanticcpg").foreach { projectName =>
    val jar = SbtHelper.findJar(s"${projectName}_2.13", updateReport, SbtHelper.JarClassifier.Sources)
    new ZipFile(jar).extractAll(inputFiles.pathAsString)
  }

  // slightly adapted from sbt's Default.scala `docTaskSettings`
  val srcs: Seq[JFile] =
    inputFiles.listRecursively
      .filter { file =>
        file.extension.contains(".java") || file.extension.contains(".scala")
      }
      .map(_.toJava)
      .toSeq

  def exportedPW(w: PrintWriter, command: String): Seq[String] => Unit =
    args => w.println((command +: args).mkString(" "))

  def exportedTS(s: TaskStreams, command: String): Seq[String] => Unit = args => {
    val w = s.text(ExportStream)
    try exportedPW(w, command)
    finally w.close()
  }

  val runDoc = Doc.scaladoc(
    label,
    s.cacheStoreFactory.sub("scala"),
    compilers.value.scalac match {
      case ac: AnalyzingCompiler => ac.onArgs(exportedTS(s, "scaladoc"))
    },
    fiOpts
  )

  runDoc(srcs, cp, out, options, maxErrors.value, s.log)

  out
}

Universal / packageBin / mappings ++= sbt.Path.directory(new File("joern-cli/src/main/resources/scripts"))

maintainer := "fabs@shiftleft.io"
