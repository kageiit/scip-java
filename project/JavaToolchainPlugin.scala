import sbt.{Compile, Def, File, _}
import sbt.Keys._
import sbt.plugins.JvmPlugin

import java.nio.file.Paths
import java.util
import java.util.Collections
import scala.util.Properties
import scala.sys.process.Process

/**
 * An sbt plugin that automatically adds the Java compiler to the boot classpath
 * when necessary.
 */
object JavaToolchainPlugin extends AutoPlugin {
  override def trigger = allRequirements
  override def requires = JvmPlugin

  object autoImport {
    lazy val javaToolchainVersion = settingKey[String](
      "The version of the Java"
    )
  }
  import autoImport._

  lazy val configSettings = List(
    javacOptions ++=
      List(
        "-target",
        "1.8",
        "-source",
        "1.8",
        "-bootclasspath",
        java8Bootclasspath()
      ),
    javacOptions.in(doc) --= List("-target", "1.8"),
    javacOptions.in(doc) --= bootclasspathSettings(javaToolchainVersion.value),
    javaHome := Some(getJavaHome(javaToolchainVersion.value)),
    javacOptions ++= bootclasspathSettings(javaToolchainVersion.value),
    javaOptions ++= bootclasspathSettings(javaToolchainVersion.value)
  )

  override lazy val projectSettings: Seq[Def.Setting[_]] =
    List(Compile, Test).flatMap(c => inConfig(c)(configSettings)) ++
      List(fork := true, javaToolchainVersion := "11")

  /**
   * For Java 8, we need to manually add the Java compiler to the boot
   * classpath.
   *
   * Newer Java versions include the compiler by default.
   */
  private def bootclasspathSettings(version: String): List[String] = {
    val home = getJavaHome(version)
    val toolsJar: File = home / "lib" / "tools.jar"
    // The tools.jar file includes the bytecode for the Java compiler in the com.sun.source package.
    // The Java compiler is available by default in Java 9+, so we only need to add tools.jar to the
    // bootclasspath for Java 8.
    if (home.toString.contains("1.8") && toolsJar.isFile) {
      List(s"-Xbootclasspath/p:$toolsJar")
    } else {
      List()
    }
  }

  private def java8Bootclasspath(): String = {
    (getJavaHome("8") / "jre" / "lib" / "rt.jar").toString
  }

  private val javaHomeCache: util.Map[String, File] = Collections
    .synchronizedMap(new util.HashMap[String, File]())
  private def getJavaHome(version: String): File = {
    javaHomeCache.computeIfAbsent(
      version,
      (v: String) => {
        val coursier = Paths.get("bin", "coursier")
        new File(
          Process(List(coursier.toString, "java-home", "--jvm", v)).!!.trim
        )
      }
    )
  }
}
