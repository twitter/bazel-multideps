package multiversion.outputs

import scala.collection.mutable

import multiversion.diagnostics.MultidepsEnrichments.XtensionDependency
import multiversion.diagnostics.MultidepsEnrichments.XtensionList
import org.typelevel.paiges.Doc

final case class DepsOutput(
    artifacts: Seq[ArtifactOutput],
    index: ResolutionIndex,
    outputIndex: collection.Map[String, ArtifactOutput]
) {
  require(artifacts.nonEmpty)
  def validate(): Unit = {
    // val names = artifacts.map()

  }
  def render: String = {
    val width = 120000
    val allTargets = index.thirdparty.dependencies2.flatMap(_.targets).toSet
    val targetToArtifacts = groupByTarget(index, artifacts)
    val thirdParties = allTargets.map(target => target -> targetToArtifacts.getOrElse(target, Nil))

    val httpFiles = Doc
      .intercalate(Doc.line, artifacts.map(_.httpFile.toDoc))
      .nested(4)
      .render(width)
    val thirdPartyImports = Doc
      .intercalate(
        Docs.blankLine,
        thirdParties.map {
          case (target, outputs) =>
            ArtifactOutput.buildThirdPartyDoc(target, index, outputs, outputIndex)
        }
      )
      .render(width)
    val builds = Doc
      .intercalate(
        Docs.blankLine,
        artifacts.map(ArtifactOutput.buildDoc(_, index, outputIndex))
      )
      .render(width)
    val evictedBuilds = Doc
      .intercalate(
        Docs.blankLine,
        index.evictionPairs.toList
          .sortBy(_._1.bazelLabel)
          .distinctBy(_._1.bazelLabel)
          .map {
            case (d, w) =>
              ArtifactOutput.buildEvictedDoc(d, w, index, outputIndex)
          }
      )
      .render(width)
    s"""# DO NOT EDIT: this file is auto-generated
def _jvm_deps_impl(ctx):
    content = \"\"\"
load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_file")

def load_jvm_deps():
    $httpFiles

\"\"\"
    ctx.file("jvm_deps.bzl", content, executable=False)
    build_content = \"\"\"
load(\"@io_bazel_rules_scala//scala:scala_import.bzl\", \"scala_import\")

$thirdPartyImports

$builds

$evictedBuilds
\"\"\"
    ctx.file("BUILD", build_content, executable=False)


jvm_deps_rule = repository_rule(
    implementation=_jvm_deps_impl,
)


def jvm_deps():
    jvm_deps_rule(name="maven")
""".stripMargin
  }

  private def groupByTarget(
      index: ResolutionIndex,
      artifacts: Seq[ArtifactOutput]
  ): collection.Map[String, Seq[ArtifactOutput]] = {
    val acc = mutable.Map.empty[String, mutable.Buffer[ArtifactOutput]]
    artifacts.foreach { art =>
      val targets = index.configsOf(art.dependency).flatMap(_.targets)
      targets.foreach { target =>
        val artifacts = acc.getOrElseUpdate(target, mutable.Buffer.empty)
        artifacts += art
      }
    }
    acc
  }
}
