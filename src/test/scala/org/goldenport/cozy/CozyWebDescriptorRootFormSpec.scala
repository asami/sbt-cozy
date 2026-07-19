package org.goldenport.cozy

import java.nio.file.Files
import org.scalatest.GivenWhenThen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sbt.*

/*
 * @since   Jul. 19, 2026
 * @version Jul. 19, 2026
 * @author  ASAMI, Tomoharu
 */
final class CozyWebDescriptorRootFormSpec extends AnyWordSpec with Matchers with GivenWhenThen {
  "Cozy Web descriptor synchronization" should {
    "insert generated operation forms into the root form section" in {
      Given("a descriptor containing both nested default.form and root form sections")
      val directory = Files.createTempDirectory("sbt-cozy-web-descriptor-root-form").toFile
      val cml = directory / "src" / "main" / "cozy" / "sample.cml"
      IO.write(
        cml,
        """# SERVICE
          |
          |## BookEditor
          |
          |### OPERATION
          |
          |#### saveBook
          |
          |- type :: COMMAND
          |- input :: SaveBook
          |- web.form :: true
          |""".stripMargin
      )
      val descriptor = directory / "src" / "main" / "web-inf" / "form.yaml"
      IO.write(
        descriptor,
        """default:
          |  form:
          |    access: authenticated
          |form:
          |""".stripMargin
      )

      When("sbt-cozy synchronizes CML Web metadata")
      CozyWebDescriptorSync.sync(directory, "sample-editor", Seq(cml), sbt.util.Logger.Null)

      Then("the generated operation is a child of root form rather than default")
      val result = IO.read(descriptor)
      val defaultsection = result.substring(0, result.indexOf("form:\n", "default:\n".length))
      val rootformsection = result.substring(result.lastIndexOf("form:\n"))
      defaultsection should not include "sample-editor.book-editor.save-book"
      rootformsection should include("sample-editor.book-editor.save-book")
    }
  }
}
