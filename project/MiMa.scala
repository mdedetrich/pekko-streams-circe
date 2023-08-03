import com.typesafe.tools.mima.plugin.MimaKeys._
import com.typesafe.tools.mima.plugin.MimaPlugin
import sbt.{AutoPlugin, CrossVersion}
import sbt.Keys._
import sbt._

import scala.collection.immutable

object MiMa extends AutoPlugin {
  override def requires = MimaPlugin

  override def trigger = allRequirements

  private def expandVersions(major: Long, minor: Long, patches: immutable.Seq[Int]): immutable.Seq[String] =
    patches.map(patch => s"$major.$minor.$patch")
  private def previousArtifacts(projectName: String, organization: String, version: String): Set[sbt.ModuleID] = {
    val Some((major, minor)) = CrossVersion.partialVersion(version)
    val currentPatchVersion  = version.split("\\.").last
    val strippedRegex        = """(\d+)(.*)""".r

    val currentPatchVersionStripped = currentPatchVersion match {
      case strippedRegex(v, _) => v.toInt
    }

    if (currentPatchVersionStripped == 0)
      Set(organization %% projectName % s"$major.$minor.0")
    else
      expandVersions(major, minor, 0 until currentPatchVersionStripped).map(v => organization %% projectName % v).toSet
  }

  override lazy val projectSettings = Seq(
    mimaReportSignatureProblems := true,
    mimaPreviousArtifacts       := previousArtifacts(name.value, organization.value, version.value)
  )
}
