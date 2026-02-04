package cchantep

import java.io.{ FileOutputStream, PrintWriter }
import java.nio.file.Path

import scala.util.control.NonFatal

import sbt._
import sbt.Keys._

import sbt.plugins.JvmPlugin

object ScaladocExtractorPlugin extends AutoPlugin {
  import scala.collection.JavaConverters._
  import org.apache.commons.io.FileUtils

  override def trigger = allRequirements
  override def requires = JvmPlugin

  object autoImport {
    val scaladocExtractorSkipToken = SettingKey[String]("scaladocExtractorSkipToken",
      """Token within '{{{' and '}}}' indicating the current code example must be skipped; default: "// not compilable"""")
  }

  override def projectSettings = Seq(
    scalacOptions in (Test, doc) ++= {
      if (scalaBinaryVersion.value == "3") {
        List("-skip-by-id:scaladocextractor")
      } else {
        List("-skip-packages", "scaladocextractor")
      }
    },
    autoImport.scaladocExtractorSkipToken := "// not compilable",
    sourceGenerators in Test += (Def.task {
      val log = streams.value.log
      val mdir = (sourceManaged in Test).value
      val stok = (autoImport.scaladocExtractorSkipToken).value
      val cacheDir = streams.value.cacheDirectory

      if (!autoScalaLibrary.value) {
        log.warn(s"Skip Scaladoc extraction on non-Scala project: ${thisProject.value.id}")
        Seq.empty
      } else {
        val srcFiles = (sourceDirectories in Compile).value.view.flatMap { d =>
          if (!d.exists || !d.isDirectory) {
            List.empty[File]
          } else {
            FileUtils.listFiles(d, Array("scala"), true).asScala.toSeq
          }
        }.toSeq

        // Track token changes to invalidate cache when configuration changes
        val tokenFile = cacheDir / "scaladoc-extractor" / "tokens"
        val currentTokens = stok
        val tokensChanged = !tokenFile.exists() ||
          IO.read(tokenFile) != currentTokens

        if (tokensChanged) {
          log.info("Configuration changed, regenerating all files...")
          // Clean the cache FIRST to force regeneration
          IO.delete(cacheDir / "scaladoc-extractor")
          // THEN write the tokens file (after directory is recreated by IO.write)
          IO.write(tokenFile, currentTokens)
        }

        // Use Tracked.diffInputs for true per-file incremental compilation
        val srcSet = srcFiles.toSet
        
        Tracked.diffInputs(
          cacheDir / "scaladoc-extractor" / "inputs",
          FilesInfo.lastModified
        )(srcSet) { changeReport =>
          // Determine which files actually changed
          val changedFiles = changeReport.modified ++ changeReport.added

          if (changedFiles.nonEmpty) {
            log.info(s"Extracting snippets from ${changedFiles.size} changed source file(s)")

            changedFiles.toSeq.flatMap { f =>
              // Find the source directory this file belongs to
              val sourceDir = (sourceDirectories in Compile).value.find { sd =>
                f.toPath.normalize().startsWith(sd.toPath.normalize())
              }
              
              sourceDir match {
                case Some(sd) =>
                  val pth = sd.toPath.normalize().relativize(f.toPath.normalize())
                  val content = scala.io.Source.fromFile(f).getLines

                  if (!content.hasNext) {
                    List.empty
                  } else {
                    log.debug(s"Extracting Scaladoc examples from $f ...")

                    val mngedPath = mdir.toPath
                    val outPath: Path = {
                      val parent = pth.getParent

                      if (parent == null) mngedPath
                      else mngedPath.resolve(parent)
                    }
                    val outDir = outPath.toFile

                    if (!outDir.exists) {
                      outDir.mkdirs()
                    }

                    parse(
                      stok, log, outDir, pth, 1L, content.next, content, List.empty)
                  }
                  
                case None =>
                  log.warn(s"Skipping $f - not in any source directory")
                  List.empty
              }
            }
          } else {
            // Return existing generated files
            (mdir ** "*.scala").get
          }
        }
      }
    }).taskValue)

  // ---

  private val tripleQuote = "\"\"\""

  @annotation.tailrec
  private def parse(
    skipTok: String,
    log: Logger,
    dir: File,
    source: Path,
    line: Long,
    head: String,
    tail: Iterator[String],
    generated: List[File]): List[File] = {
    val q1 = head.indexOf(tripleQuote)
    val q2 = head.indexOf("\"")
    val si = head.indexOf("/**")

    if (q1 >= 0 && (si < 0 || q1 < si)) {
      parseString(
        skipTok, log, dir, source, line,
        tripleQuote, head.drop(q1 + tripleQuote.size), tail, generated)

    } else if (q2 >= 0 && (si < 0 || q2 < si)) {
      parseString(
        skipTok, log, dir, source, line,
        "\"", head.drop(q2 + 1), tail, generated)

    } else if (si >= 0) {
      parseScaladoc(
        skipTok, log, dir, source, line, head.drop(si + 3), tail, generated)

    } else if (tail.hasNext) {
      parse(skipTok, log, dir, source, line + 1L, tail.next, tail, generated)
    } else {
      generated
    }
  }

  private def parseString(
    skipTok: String,
    log: Logger,
    dir: File,
    source: Path,
    line: Long,
    delimiter: String,
    head: String,
    tail: Iterator[String],
    generated: List[File]): List[File] = {
    val i = head.indexOf(delimiter)

    if (i >= 0) {
      parse(
        skipTok, log, dir, source, line,
        head.drop(i + delimiter.size), tail, generated)

    } else if (tail.hasNext) {
      parse(skipTok, log, dir, source, line + 1L, tail.next, tail, generated)
    } else {
      generated
    }
  }

  @annotation.tailrec
  private def parseScaladoc(
    skipTok: String,
    log: Logger,
    dir: File,
    source: Path,
    line: Long,
    head: String,
    tail: Iterator[String],
    generated: List[File]): List[File] = {

    val e = head.indexOf("*/")
    val s = head.indexOf("{{{")

    if (e >= 0 && (s < 0 || e < s)) {
      parse(skipTok, log, dir, source, line, head.drop(e + 2), tail, generated)
    } else if (s > 0) {
      val name = source.getFileName.toString.dropRight(6)
      val f = dir / s"scaladoc-${name}-${line}.scala"

      log.debug(s"Snippet found at line $line (${source}.scala)")

      val p = new PrintWriter(new FileOutputStream(f))

      try {
        p.println("package scaladocextractor\r\n")
        p.println(s"/* ${source}, ln $line */")
        p.println(s"object ${name}${line}Snippet {")
        p.flush()

        parseSnippet(
          skipTok, log, dir, source, line,
          f, p, head.drop(s + 3), tail, generated)

      } catch {
        case NonFatal(cause) =>
          log.error(s"""Fails to parse snippet: ${source}, ln $line: ${cause.getMessage}""")

          throw cause
      } finally {
        try {
          p.close()
        } catch {
          case NonFatal(cause) =>
        }
      }
    } else if (!tail.hasNext) {
      generated
    } else {
      parseScaladoc(
        skipTok, log, dir, source, line + 1L, tail.next, tail, generated)
    }
  }

  @annotation.tailrec
  private def parseSnippet(
    skipTok: String,
    log: Logger,
    dir: File,
    source: Path,
    line: Long,
    f: File,
    out: PrintWriter,
    head: String,
    tail: Iterator[String],
    generated: List[File]): List[File] = {
    val s = head.indexOf(skipTok)
    val e = head.indexOf("}}}")
    val i = head.indexOf("*/")

    if (s >= 0 && (e < 0 || s < e) && (i < 0 || s < i)) {
      log.info(s"Skip Scaladoc snippet at line $line ($source): ${cleanSnippet(head)}")

      parseScaladoc(
        skipTok, log, dir, source, line, head.drop(s + skipTok.size), tail,
        generated /* do not register the skipped snippet */ )

    } else if (i >= 0 && (e < 0 || i < e)) {
      val msg = s"Premature end of Scaladoc snippet at line $line (${source})"

      log.warn(msg)
      out.println(s"// $msg")

      parseScaladoc(
        skipTok, log, dir, source, line, head.drop(i + 2), tail,
        generated /* do not register the generated snippet in this case */ )

    } else if (e >= 0) {
      val (code, rem) = head.splitAt(e)

      out.println(cleanSnippet(code))
      out.println("}" /* enclosing snippet object */ )

      out.flush()
      out.close()

      log.debug(s"Generating $f")

      parseScaladoc(
        skipTok, log, dir, source, line, rem.drop(3), tail, f :: generated)

    } else {
      out.println(cleanSnippet(head))

      if (tail.hasNext) {
        parseSnippet(
          skipTok, log, dir, source, line + 1L,
          f, out, tail.next, tail, generated)

      } else {
        generated
      }
    }
  }

  private def cleanSnippet(in: String): String =
    in.dropWhile(_.isSpaceChar).stripPrefix("*")//. // strip prefix '^[ ]*\*'
      //replaceAll("""\\""", "") // unescape
}
