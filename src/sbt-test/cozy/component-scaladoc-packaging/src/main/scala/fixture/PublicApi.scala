package fixture

/** A small public API used to exercise Component Scaladoc packaging. */
final class PublicApi {
  /** Returns a stable greeting for the supplied name. */
  def greet(name: String): String = s"Hello, ${normalize(name)}!"

  private def normalize(name: String): String = name.trim
}
