import sbt._
import Keys._
import com.typesafe.sbt.pgp.PgpKeys._
import org.scalajs.sbtplugin.ScalaJSPlugin
import ScalaJSPlugin._
import ScalaJSPlugin.autoImport._
import org.scalajs.jsenv.JSEnv

object ScalajsReact {

  object Ver {
    val Scala211      = "2.11.8"
    val Scala212      = "2.12.1"
    val ScalaJsDom    = "0.9.1"
    val ReactJs       = "15.3.2"
    val Monocle       = "1.3.2"
    val Scalaz72      = "7.2.7"
    val MTest         = "0.4.4"
    val MacroParadise = "2.1.0"
    val SizzleJs      = "2.3.0"
    val Nyaya         = "0.8.1"
  }

  type PE = Project => Project

  val clearScreenTask = TaskKey[Unit]("clear", "Clears the screen.")

  def byScalaVersion[A](f: PartialFunction[(Int, Int), Seq[A]]): Def.Initialize[Seq[A]] =
    Def.setting(CrossVersion.partialVersion(scalaVersion.value).flatMap(f.lift).getOrElse(Nil))

  def commonSettings: PE =
    _.enablePlugins(ScalaJSPlugin)
      .settings(
        organization       := "com.github.japgolly.scalajs-react",
        homepage           := Some(url("https://github.com/japgolly/scalajs-react")),
        licenses           += ("Apache-2.0", url("http://opensource.org/licenses/Apache-2.0")),
        scalaVersion       := Ver.Scala211, // pending https://issues.scala-lang.org/browse/SI-10168
        crossScalaVersions := Seq(Ver.Scala211, Ver.Scala212),
        scalacOptions     ++= Seq("-deprecation", "-unchecked", "-feature",
                                "-language:postfixOps", "-language:implicitConversions",
                                "-language:higherKinds", "-language:existentials")
                                ++ byScalaVersion {
                                  case (2, 12) => Seq("-opt:l:method")
                                  // case (2, 12) => Seq("-opt:l:project", "-opt-warnings:at-inline-failed")
                                }.value,
        //scalacOptions    += "-Xlog-implicits",
        updateOptions      := updateOptions.value.withCachedResolution(true),
        incOptions         := incOptions.value.withNameHashing(true).withLogRecompileOnMacro(false),
        triggeredMessage   := Watched.clearWhenTriggered,
        clearScreenTask    := { println("\033[2J\033[;H") })

  def preventPublication: PE =
    _.settings(
      publishTo := Some(Resolver.file("Unused transient repository", target.value / "fakepublish")),
      publishArtifact := false,
      publishLocalSigned := (),       // doesn't work
      publishSigned := (),            // doesn't work
      packagedArtifacts := Map.empty) // doesn't work - https://github.com/sbt/sbt-pgp/issues/42

  def publicationSettings: PE =
    _.settings(
      publishTo := {
        val nexus = "https://oss.sonatype.org/"
        if (isSnapshot.value)
          Some("snapshots" at nexus + "content/repositories/snapshots")
        else
          Some("releases"  at nexus + "service/local/staging/deploy/maven2")
      },
      pomExtra :=
        <scm>
          <connection>scm:git:github.com/japgolly/scalajs-react</connection>
          <developerConnection>scm:git:git@github.com:japgolly/scalajs-react.git</developerConnection>
          <url>github.com:japgolly/scalajs-react.git</url>
        </scm>
        <developers>
          <developer>
            <id>japgolly</id>
            <name>David Barri</name>
          </developer>
        </developers>)
    .configure(sourceMapsToGithub)

  def sourceMapsToGithub: PE =
    p => p.settings(
      scalacOptions ++= (if (isSnapshot.value) Seq.empty else Seq({
        val a = p.base.toURI.toString.replaceFirst("[^/]+/?$", "")
        val g = "https://raw.githubusercontent.com/japgolly/scalajs-react"
        s"-P:scalajs:mapSourceURI:$a->$g/v${version.value}/"
      }))
    )

  val setupJsEnv: Project => Project =
    sys.env.get("JSENV").map(_.toLowerCase.replaceFirst("js$", "")) match {
      case Some("phantom") | None => _.settings(jsEnv in Test := new PhantomJS2Env(scalaJSPhantomJSClassLoader.value))
      case Some("node")           => identity
      case Some(x)                => sys error s"Unsupported JsEnv: $x"
    }

  def utestSettings: PE =
    _.configure(useReactJs("test"), setupJsEnv)
      .settings(
        scalacOptions in Test += "-language:reflectiveCalls",
        libraryDependencies   += "com.lihaoyi" %%% "utest" % Ver.MTest % "test",
        testFrameworks        += new TestFramework("utest.runner.Framework"),
        requiresDOM           := true)

  def useReactJs(scope: String = "compile"): PE =
    _.settings(
      jsDependencies ++= Seq(

        "org.webjars.bower" % "react" % Ver.ReactJs % scope
          /        "react-with-addons.js"
          minified "react-with-addons.min.js"
          commonJSName "React",

        "org.webjars.bower" % "react" % Ver.ReactJs % scope
          /         "react-dom.js"
          minified  "react-dom.min.js"
          dependsOn "react-with-addons.js"
          commonJSName "ReactDOM",

        "org.webjars.bower" % "react" % Ver.ReactJs % scope
          /         "react-dom-server.js"
          minified  "react-dom-server.min.js"
          dependsOn "react-dom.js"
          commonJSName "ReactDOMServer"),

      skip in packageJSDependencies := false)

  def addCommandAliases(m: (String, String)*) = {
    val s = m.map(p => addCommandAlias(p._1, p._2)).reduce(_ ++ _)
    (_: Project).settings(s: _*)
  }

  def extModuleName(shortName: String): PE =
    _.settings(name := s"ext-$shortName")

  def definesMacros: Project => Project =
    _.settings(
      scalacOptions += "-language:experimental.macros",
      libraryDependencies ++= Seq(
        "org.scala-lang" % "scala-reflect"  % scalaVersion.value,
        "org.scala-lang" % "scala-compiler" % scalaVersion.value % "provided"))

  def macroParadisePlugin =
    compilerPlugin("org.scalamacros" % "paradise" % Ver.MacroParadise cross CrossVersion.full)

  def hasNoTests: Project => Project =
    _.settings(
      sbt.Keys.test in Test := (),
      testOnly      in Test := (),
      testQuick     in Test := ())

  // ==============================================================================================
  lazy val root = Project("root", file("."))
    // .aggregate(core, test, scalaz72, monocle, extra, ghpagesMacros, ghpages)
    .aggregate(core)
    .configure(commonSettings, preventPublication, hasNoTests, addCommandAliases(
      "/"   -> "project root",
      "L"   -> "root/publishLocal",
      "C"   -> "root/clean",
      "T"   -> ";root/clean;root/test",
      "c"   -> "compile",
      "tc"  -> "test:compile",
      "t"   -> "test",
      "tq"  -> "testQuick",
      "to"  -> "test-only",
      "cc"  -> ";clean;compile",
      "ctc" -> ";clean;test:compile",
      "ct"  -> ";clean;test"))

  // ==============================================================================================
  lazy val core = project
    .configure(commonSettings, publicationSettings, definesMacros, utestSettings, InBrowserTesting.js)
    .settings(
      name := "core",
      libraryDependencies ++= Seq(
        "org.scala-js" %%% "scalajs-dom" % Ver.ScalaJsDom,
        "org.scalaz"   %%% "scalaz-core" % Ver.Scalaz72 % "test"),
      jsDependencies ++= Seq(
        (ProvidedJS / "component-es3.js" dependsOn "react-dom.js") % Test,
        (ProvidedJS / "component-fn.js" dependsOn "react-dom.js") % Test))

//  lazy val extra = project
//    .configure(commonSettings, publicationSettings, definesMacros, hasNoTests)
//    .dependsOn(core)
//    .settings(name := "extra")
//
//  lazy val test = project
//    .configure(commonSettings, publicationSettings, utestSettings, InBrowserTesting.js)
//    .dependsOn(core, extra, monocle)
//    .settings(
//      name := "test",
//      libraryDependencies ++= Seq(
//        "com.github.japgolly.nyaya" %%% "nyaya-prop" % Ver.Nyaya % "test",
//        "com.github.japgolly.nyaya" %%% "nyaya-gen"  % Ver.Nyaya % "test",
//        "com.github.japgolly.nyaya" %%% "nyaya-test" % Ver.Nyaya % "test",
//        monocleLib("macro") % "test"),
//      jsDependencies ++= Seq(
//        (ProvidedJS / "sampleReactComponent.js" dependsOn "react-dom.js") % Test, // for JS Component Type Test.
//        "org.webjars.bower" % "sizzle" % Ver.SizzleJs % Test / "sizzle.min.js" commonJSName "Sizzle"),
//      addCompilerPlugin(macroParadisePlugin))

  // ==============================================================================================
//  def scalazModule(name: String, version: String) = {
//    val shortName = name.replaceAll("[^a-zA-Z0-9]+", "")
//    Project(shortName, file(name))
//      .configure(commonSettings, publicationSettings, extModuleName(shortName), hasNoTests)
//      .dependsOn(core, extra)
//      .settings(
//        libraryDependencies += "org.scalaz" %%% "scalaz-effect" % version)
//  }
//
//  lazy val scalaz72 = scalazModule("scalaz-7.2", Ver.Scalaz72)

  // ==============================================================================================
//  lazy val monocle = project
//    .configure(commonSettings, publicationSettings, extModuleName("monocle"), hasNoTests)
//    .dependsOn(core, extra, scalaz72)
//    .settings(libraryDependencies += monocleLib("core"))
//
//  def monocleLib(name: String) =
//    "com.github.julien-truffaut" %%%! s"monocle-$name" % Ver.Monocle

  // ==============================================================================================
//  lazy val ghpagesMacros = Project("gh-pages-macros", file("gh-pages-macros"))
//    .configure(commonSettings, preventPublication, hasNoTests, definesMacros)
//
//  lazy val ghpages = Project("gh-pages", file("gh-pages"))
//    .dependsOn(core, extra, monocle, ghpagesMacros)
//    .configure(commonSettings, useReactJs(), preventPublication, hasNoTests)
//    .settings(
//      libraryDependencies += monocleLib("macro"),
//      addCompilerPlugin(macroParadisePlugin),
//      emitSourceMaps := false,
//      artifactPath in (Compile, fullOptJS) := file("gh-pages/res/ghpages.js"))
}
