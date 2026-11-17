sbtPlugin := true

name := "sbt-scaladoc-compiler"

organization := "cchantep"

version := "0.9"

scalaVersion := "2.12.21"
crossScalaVersions := Vector("2.12.21", "3.9.0")

pluginCrossBuild / sbtVersion := {
  if (scalaBinaryVersion.value == "2.12") "1.12.3" else "2.1.0"
}
scalacOptions ++= {
  if (scalaBinaryVersion.value == "2.12") Seq("-Xsource:3", "-release:8")
  else Nil
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
