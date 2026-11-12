import org.jetbrains.sbtidea.Keys._

ThisBuild / scalaVersion := "3.3.8"
ThisBuild / intellijPluginName := "oap-intellij-hocon"
ThisBuild / intellijBuild := "263.7307.200"
ThisBuild / githubWorkflowJavaVersions := Seq(JavaSpec.temurin("25"))
// Cache the IntelliJ SDK (~800MB) that the sbt-idea-plugin downloads on build load; the default
// sbt cache doesn't cover it. Keyed on build.sbt so an intellijBuild bump invalidates the entry.
ThisBuild / githubWorkflowBuildPreamble += WorkflowStep.Use(
  UseRef.Public("actions", "cache", "v4"),
  name = Some("Cache IntelliJ SDK"),
  params = Map(
    "path" -> "~/.intellij-hoconPluginIC",
    "key" -> "${{ runner.os }}-intellij-sdk-${{ hashFiles('build.sbt') }}",
    "restore-keys" -> "${{ runner.os }}-intellij-sdk-",
  ),
)
// Run scalafmt inside the build job (not a separate job) so it shares this job's single SDK
// download/restore instead of needing its own.
ThisBuild / githubWorkflowBuildPreamble += WorkflowStep.Sbt(
  List("scalafmtCheckAll", "scalafmtSbtCheck"),
  name = Some("Check Scala formatting"),
)
ThisBuild / autoRemoveOldCachedIntelliJSDK := true
ThisBuild / autoRemoveOldCachedDownloads := true
ThisBuild / githubWorkflowPublishTargetBranches := Seq.empty

val junitInterfaceVersion = "0.13.3"
val junitVersion = "4.13.2"
val commonsTextVersion = "1.15.0"
val opentest4jVersion = "1.3.0"

lazy val hocon = project
  .in(file("."))
  .enablePlugins(SbtIdeaPlugin)
  .settings(
    version := sys.env.getOrElse("PLUGIN_VERSION", "2026.2.6-OAP"),
    Compile / scalaSource := baseDirectory.value / "src",
    Test / scalaSource := baseDirectory.value / "test",
    Compile / resourceDirectory := baseDirectory.value / "resources",
    // Matches HoconSingleModuleTest's rootPath, so classpath(...)/include resolution behaves the same
    // when editing these fixtures directly in this IDE window as it does under the JUnit sandbox.
    Test / unmanagedResourceDirectories += baseDirectory.value / "testdata" / "includes" / "singlemodule",
    // Built on JDK 25 (2026.3 platform classes are Java 25 bytecode) but still emitting Java 24 bytecode,
    // as before the platform bump.
    Global / javacOptions ++= Seq("--release", "24"),
    Global / scalacOptions ++= Seq(
      "-release",
      "24",
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xfatal-warnings",
    ),
    ideBasePackages := Seq("org.jetbrains.plugins.hocon"),
    // com.intellij.structureView: since 2026.3 the structure view API (PsiTreeElementBase etc) lives in
    // its own bundled plugin rather than in the platform core.
    intellijPlugins := Seq(
      "com.intellij.java",
      "com.intellij.java-i18n",
      "com.intellij.modules.json",
      "com.intellij.structureView",
    ).map(_.toPlugin),
    // Everything after Kotlin: since 2026.3 these are separate bundled plugins that the Java plugin and
    // the platform core need in order to load in the test sandbox.
    intellijExtraRuntimePluginsInTests := Seq(
      "org.jetbrains.kotlin",
      "com.intellij.xml",
      "com.intellij.platform.vcs",
      "intellij.java.aetherDependencyResolver.plugin",
      "com.intellij.platform.testRunner",
      "com.intellij.properties",
    ).map(_.toPlugin),
    resolvers += "JetBrains Intellij Repository".at("https://www.jetbrains.com/intellij-repository/snapshots"),
    libraryDependencies ++= Seq(
      "org.apache.commons" % "commons-text" % commonsTextVersion,
      "com.github.sbt" % "junit-interface" % junitInterfaceVersion % Test,
      "junit" % "junit" % junitVersion % Test,
      "org.opentest4j" % "opentest4j" % opentest4jVersion % Test,
      ("com.jetbrains.intellij.platform" % "test-framework" % intellijBuild.value % Test).intransitive(),
      ("com.jetbrains.intellij.java" % "java-test-framework" % intellijBuild.value % Test).intransitive(),
      ("com.jetbrains.intellij.java" % "java-test-framework-shared" % intellijBuild.value % Test).intransitive(),
      ("com.jetbrains.intellij.platform" % "test-framework-core" % intellijBuild.value % Test).intransitive(),
      ("com.jetbrains.intellij.platform" % "test-framework-common" % intellijBuild.value % Test).intransitive(),
    ),
    packageLibraryMappings := Seq.empty, // allow scala-library
    patchPluginXml := pluginXmlOptions { xml =>
      xml.version = version.value
    },
  )
