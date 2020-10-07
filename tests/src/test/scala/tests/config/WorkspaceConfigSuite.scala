package tests.config

import java.io.ByteArrayOutputStream
import java.io.PrintStream

import moped.json.ErrorResult
import moped.json.ValueResult
import moped.reporters.ConsoleReporter
import moped.reporters.Input
import multideps.config.WorkspaceConfig
import multideps.config._
import munit.TestOptions

class WorkspaceConfigSuite extends BaseSuite {
  val out = new ByteArrayOutputStream()
  val reporter: ConsoleReporter = ConsoleReporter(new PrintStream(out))
  def check(
      name: TestOptions,
      original: String,
      expected: WorkspaceConfig
  )(implicit loc: munit.Location): Unit = {
    test(name) {
      reporter.reset()
      WorkspaceConfig.parse(
        Input.filename(name.name + ".yaml", original)
      ) match {
        case ValueResult(obtained) =>
          assertEquals(obtained, expected)
        case ErrorResult(error) =>
          reporter.log(error)
          fail(out.toString())
      }
    }
  }

  check(
    "basic",
    """|dependencies:
       |  - organization: org.scalameta
       |    artifact: munit
       |    version: "0.7.13"
       |    lang: scala
       |""".stripMargin,
    WorkspaceConfig(
      dependencies = List(
        DependencyConfig(
          organization = "org.scalameta",
          artifact = "munit",
          version = VersionsConfig("0.7.13"),
          lang = ScalaLanguagesConfig
        )
      )
    )
  )

  check(
    "custom-version",
    """|dependencies:
       |  - organization: org.scalameta
       |    artifact: munit
       |    version:
       |      default: "0.7.13"
       |      old: "0.6.9"
       |    lang: scala
       |""".stripMargin,
    WorkspaceConfig(
      dependencies = List(
        DependencyConfig(
          organization = "org.scalameta",
          artifact = "munit",
          version = VersionsConfig("0.7.13", Map("old" -> "0.6.9")),
          lang = ScalaLanguagesConfig
        )
      )
    )
  )
}