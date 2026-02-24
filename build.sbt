sbtPlugin := true

name := "sbt-scaladoc-compiler"

organization := "cchantep"

version := "0.6"

crossSbtVersions := Vector("0.13.11", "1.3.1")

libraryDependencies ++= {
  val sv = scalaBinaryVersion.value

  val specsVer = sv match {
    case "2.10" =>
      "3.10.0"

    case "2.11" =>
      "4.10.6"

    case _ =>
      "4.21.0"
  }

  Seq(
    "commons-io" % "commons-io" % "2.6",
    "org.specs2" %% "specs2-core" % specsVer % Test
  )
}

ThisBuild / publishTo := sys.env.get("REPO_PATH").map { path =>
  import Resolver.ivyStylePatterns

  val repoDir = new java.io.File(path)

  Resolver.file("repo", repoDir)
}
