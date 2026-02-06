sbtPlugin := true

name := "sbt-scaladoc-compiler"

organization := "cchantep"

version := "0.5"

crossSbtVersions := Vector("0.13.11", "1.3.1")

libraryDependencies += "commons-io" % "commons-io" % "2.6"

ThisBuild / publishTo := sys.env.get("REPO_PATH").map { path =>
  import Resolver.ivyStylePatterns

  val repoDir = new java.io.File(path)

  Resolver.file("repo", repoDir)
}
