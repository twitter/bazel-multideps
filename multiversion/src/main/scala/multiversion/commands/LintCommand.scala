package multiversion.commands

import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import scala.collection.JavaConverters._

import com.twitter.multiversion.Build.QueryResult
import moped.annotations.CommandName
import moped.annotations.Description
import moped.annotations.PositionalArguments
import moped.cli.Application
import moped.cli.Command
import moped.cli.CommandParser
import moped.json.Result
import moped.json.ValueResult
import moped.progressbars.InteractiveProgressBar
import moped.progressbars.ProcessRenderer
import multiversion.diagnostics.MultidepsEnrichments._
import multiversion.indexes.DependenciesIndex
import multiversion.indexes.TargetIndex
import multiversion.loggers.ProgressBars
import multiversion.loggers.StaticProgressRenderer
import multiversion.outputs.LintOutput
import multiversion.resolvers.SimpleDependency
import org.typelevel.paiges.Doc

@CommandName("lint")
case class LintCommand(
    @Description("File to write lint report") lintReportPath: Option[Path] = None,
    @PositionalArguments queryExpressions: List[String] = Nil,
    app: Application = Application.default
) extends Command {
  private def runQuery(queryExpression: String): Result[QueryResult] = {
    val command = List(
      "bazel",
      "query",
      queryExpression,
      "--noimplicit_deps",
      "--notool_deps",
      "--output=proto"
    )
    val pr0 = new ProcessRenderer(command, command, clock = app.env.clock)
    val pr = StaticProgressRenderer.ifAnsiDisabled(
      pr0,
      app.env.isColorEnabled
    )
    val pb = new InteractiveProgressBar(
      out = new PrintWriter(app.env.standardError),
      renderer = pr
    )
    val process = ProgressBars.run(pb) {
      os.proc(command)
        .call(
          cwd = os.Path(app.env.workingDirectory),
          stderr = pr0.output,
          check = false
        )
    }
    if (process.exitCode == 0) {
      ValueResult(QueryResult.parseFrom(process.out.bytes))
    } else {
      pr0.asErrorResult(process.exitCode)
    }
  }

  def run(): Int = app.complete(runResult())

  def runResult(): Result[Unit] = {
    val expr = queryExpressions.mkString(" ")
    for {
      result <- runQuery(s"allpaths($expr, @maven//:all)")
      rootsResult <- runQuery(expr)
    } yield {
      val roots = rootsResult.getTargetList().asScala.map(_.getRule().getName())
      val index = new DependenciesIndex(result)
      val lintResults = roots.map { root =>
        val deps = index.dependencies(root)
        val errors = deps.groupBy(_.dependency.map(_.module)).collect {
          case (Some(dep), ts) if ts.size > 1 =>
            dep -> ts.collect {
              case TargetIndex(_, _, _, Some(dep)) => dep.version
            }
        }
        val isTransitive = errors.toList.flatMap {
          case (m, vs) =>
            for {
              v <- vs
              dep = SimpleDependency(m, v)
              tdep <- index.dependencies(dep)
              if tdep.dependency != Some(dep)
            } yield tdep
        }.toSet

        val reportedErrors = errors.filter {
          case (module, versions) =>
            val deps = versions
              .map(v => SimpleDependency(module, v))
              .flatMap(index.byDependency.get(_))
            !deps.exists(isTransitive)
        }

        LintOutput(root, reportedErrors)
      }

      for {
        LintOutput(root, errors) <- lintResults
        (module, versions) <- errors
      } {
        app.reporter.error(
          s"target '$root' depends on conflicting versions of the 3rdparty dependency '${module.repr}:{${versions.commas}}'.\n" +
            s"\tTo fix this problem, modify the dependency list of this target so that it only depends on one version of the 3rdparty module '${module.repr}'"
        )
      }

      lintReportPath
        .map(p => if (p.isAbsolute()) p else app.env.workingDirectory.resolve(p))
        .foreach { out =>
          val docs = lintResults.filter(_.conflicts.nonEmpty).map(_.toDoc)
          val rendered = Doc.intercalate(Doc.line, docs).render(120)
          Files.createDirectories(out.getParent())
          Files.write(out, rendered.getBytes(StandardCharsets.UTF_8))
        }
    }
  }
}

object LintCommand {
  val default: LintCommand = LintCommand()
  implicit val parser: CommandParser[LintCommand] =
    CommandParser.derive(default)
}
