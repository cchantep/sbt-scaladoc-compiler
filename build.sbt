sbtPlugin := true

name := "sbt-scaladoc-compiler"

organization := "cchantep"

version := "0.8"

crossSbtVersions := Vector("1.12.3", "2.0.0")

scalaVersion := {
  val v = (pluginCrossBuild / sbtVersion).value

  if (v startsWith "2.") {
    "3.3.8"
  } else {
    "2.12.20"
  }
}

libraryDependencies ++= Seq(
  "commons-io" % "commons-io" % "2.6",
  "org.specs2" %% "specs2-core" % "4.21.0" % Test
)

ThisBuild / publishTo := sys.env.get("REPO_PATH").map { path =>
  import Resolver.ivyStylePatterns

  val repoDir = new java.io.File(path)

  Resolver.file("repo", repoDir)
}
