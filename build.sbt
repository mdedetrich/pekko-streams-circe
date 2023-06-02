import com.jsuereth.sbtpgp.PgpKeys.publishSigned

name := "pekko-streams-circe"

val scala213Version = "2.13.10"
val scala212Version = "2.12.17"

ThisBuild / resolvers += Resolver.ApacheMavenSnapshotsRepo

val circeVersion     = "0.14.5"
val pekkoVersion     = "0.0.0+26669-ec5b6764-SNAPSHOT"
val pekkoHttpVersion = "0.0.0+4411-6fe04045-SNAPSHOT"
val jawnVersion      = "1.5.0"
val scalaTestVersion = "3.2.16"

ThisBuild / crossScalaVersions   := Seq(scala212Version, scala213Version)
ThisBuild / scalaVersion         := (ThisBuild / crossScalaVersions).value.last
ThisBuild / organization         := "org.mdedetrich"
ThisBuild / mimaFailOnNoPrevious := false // Set this to true when we start caring about binary compatibility
ThisBuild / versionScheme        := Some(VersionScheme.EarlySemVer)

lazy val streamJson = project
  .in(file("stream-json"))
  .settings(
    name := "pekko-stream-json",
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-stream" % pekkoVersion % Provided,
      "org.typelevel"    %% "jawn-parser"  % jawnVersion
    )
  )

lazy val httpJson = project
  .in(file("http-json"))
  .settings(
    name := "pekko-http-json",
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-stream" % pekkoVersion     % Provided,
      "org.apache.pekko" %% "pekko-http"   % pekkoHttpVersion % Provided
    )
  )
  .dependsOn(streamJson)

lazy val streamCirce = project
  .in(file("support") / "stream-circe")
  .settings(
    name := "pekko-stream-circe",
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-stream" % pekkoVersion % Provided,
      "io.circe"         %% "circe-jawn"   % circeVersion
    )
  )
  .dependsOn(streamJson)

lazy val httpCirce = project
  .in(file("support") / "http-circe")
  .settings(
    name := "pekko-http-circe",
    libraryDependencies ++= Seq(
      "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion % Provided
    )
  )
  .dependsOn(streamCirce, httpJson)

lazy val parent = project
  .in(file("."))
  .dependsOn(httpJson, httpCirce)
  .aggregate(streamJson, httpJson, streamCirce, httpCirce, tests)
  .settings(
    publish / skip       := true,
    publishSigned / skip := true,
    publishLocal / skip  := true
  )

lazy val tests = project
  .in(file("tests"))
  .dependsOn(streamJson, httpJson, streamCirce, httpCirce)
  .settings(
    libraryDependencies ++=
      List(
        "org.apache.pekko" %% "pekko-stream"  % pekkoVersion     % Test,
        "org.apache.pekko" %% "pekko-http"    % pekkoHttpVersion % Test,
        "org.scalatest"    %% "scalatest"     % scalaTestVersion % Test,
        "io.circe"         %% "circe-generic" % circeVersion     % Test
      ),
    publish / skip       := true,
    publishSigned / skip := true,
    publishLocal / skip  := true
  )

ThisBuild / scalacOptions ++= Seq(
  "-release:8",
  "-encoding",
  "UTF-8",
  "-deprecation", // warning and location for usages of deprecated APIs
  "-feature",     // warning and location for usages of features that should be imported explicitly
  "-unchecked",   // additional warnings where generated code depends on assumptions
  "-Xlint",       // recommended additional warnings
  "-Xcheckinit", // runtime error when a val is not initialized due to trait hierarchies (instead of NPE somewhere else)
  "-Ywarn-value-discard", // Warn when non-Unit expression results are unused
  "-Ywarn-dead-code",
  "-language:postfixOps"
)

Defaults.itSettings

configs(IntegrationTest)

ThisBuild / homepage := Some(url("https://github.com/mdedetrich/pekko-streams-circe"))

ThisBuild / scmInfo := Some(
  ScmInfo(url("https://github.com/mdedetrich/pekko-streams-circe"), "git@github.com:mdedetrich/pekko-streams-circe.git")
)

ThisBuild / developers := List(
  Developer("knutwalker", "Paul Horn", "", url("https://github.com/knutwalker/")),
  Developer("mdedetrich", "Matthew de Detrich", "mdedetrich@gmail.com", url("https://github.com/mdedetrich"))
)

ThisBuild / licenses += ("Apache-2.0", url("https://opensource.org/licenses/Apache-2.0"))

ThisBuild / publishMavenStyle      := true
ThisBuild / publishTo              := sonatypePublishToBundle.value
ThisBuild / test / publishArtifact := false
ThisBuild / pomIncludeRepository   := (_ => false)

val flagsFor12 = Seq(
  "-Xlint:_",
  "-Ywarn-infer-any",
  "-Ywarn-adapted-args", // Warn if an argument list is modified to match the receiver
  "-Ywarn-inaccessible",
  "-Ywarn-infer-any",
  "-opt-inline-from:<sources>",
  "-opt:l:inline"
)

val flagsFor13 = Seq(
  "-Xlint:_",
  "-opt-inline-from:<sources>",
  "-opt:l:inline"
)

ThisBuild / scalacOptions ++= {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, n)) if n == 13 =>
      flagsFor13
    case Some((2, n)) if n == 12 =>
      flagsFor12
  }
}

IntegrationTest / parallelExecution := false

ThisBuild / githubWorkflowTargetBranches := Seq("main") // Once we have branches per version, add the pattern here

ThisBuild / githubWorkflowBuild := Seq(
  WorkflowStep.Sbt(List("mimaReportBinaryIssues"), name = Some("Report binary compatibility issues")),
  WorkflowStep.Sbt(List("clean", "coverage", "test"), name = Some("Build project"))
)

ThisBuild / githubWorkflowBuildPostamble ++= Seq(
  // See https://github.com/scoverage/sbt-coveralls#github-actions-integration
  WorkflowStep.Sbt(
    List("coverageReport", "coverageAggregate", "coveralls"),
    name = Some("Upload coverage data to Coveralls"),
    env = Map(
      "COVERALLS_REPO_TOKEN" -> "${{ secrets.GITHUB_TOKEN }}",
      "COVERALLS_FLAG_NAME"  -> "Scala ${{ matrix.scala }}"
    )
  )
)

// This is causing problems with env variables being passed in, see
// https://github.com/sbt/sbt/issues/6468
ThisBuild / githubWorkflowUseSbtThinClient := false

ThisBuild / githubWorkflowPublish := Seq(
  WorkflowStep.Sbt(
    commands = List("ci-release"),
    name = Some("Publish project"),
    env = Map(
      "PGP_PASSPHRASE"    -> "${{ secrets.PGP_PASSPHRASE }}",
      "PGP_SECRET"        -> "${{ secrets.PGP_SECRET }}",
      "SONATYPE_PASSWORD" -> "${{ secrets.SONATYPE_PASSWORD }}",
      "SONATYPE_USERNAME" -> "${{ secrets.SONATYPE_USERNAME }}"
    )
  )
)

ThisBuild / githubWorkflowTargetTags ++= Seq("v*")
ThisBuild / githubWorkflowPublishTargetBranches :=
  Seq(
    RefPredicate.StartsWith(Ref.Tag("v")),
    RefPredicate.Equals(Ref.Branch("main"))
  )

ThisBuild / githubWorkflowJavaVersions := List(
  JavaSpec.temurin("8"),
  JavaSpec.temurin("11"),
  JavaSpec.temurin("17")
)
