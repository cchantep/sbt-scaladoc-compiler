package cchantep

import java.io.{ File => JFile }
import java.nio.file.Path

import scala.util.control.NonFatal

import sbt._
import sbt.Keys._

import sbt.plugins.JvmPlugin

object ScaladocExtractorPlugin extends AutoPlugin {
  import scala.collection.JavaConverters._
  import org.apache.commons.io.FileUtils
  import org.apache.commons.io.filefilter.IOFileFilter

  override def trigger = allRequirements
  override def requires = JvmPlugin

  object autoImport {
    val scaladocExtractorSkipToken = SettingKey[String]("scaladocExtractorSkipToken",
      """Token within '{{{' and '}}}' indicating the current code example must be skipped; default: "// not compilable"""")

    val scaladocExtractorIncludes = SettingKey[FileFilter]("scaladocExtractorIncludes",
      """File filter for files to be processed; default: *.scala""")

    val scaladocExtractorExcludes = SettingKey[FileFilter]("scaladocExtractorExcludes",
      """File filter for files to be excluded; default: <none>""")
  }

  override def projectSettings = Seq(
    Test / doc / scalacOptions ++= {
      if (scalaBinaryVersion.value == "3") {
        List("-skip-by-id:scaladocextractor")
      } else {
        List("-skip-packages", "scaladocextractor")
      }
    },
    autoImport.scaladocExtractorSkipToken := "// not compilable",
    autoImport.scaladocExtractorIncludes := "*.scala",
    autoImport.scaladocExtractorExcludes := NothingFilter,
    Test / sourceGenerators += (Def.task {
      val log = streams.value.log
      val mdir = (Test / sourceManaged).value
      val stok = (autoImport.scaladocExtractorSkipToken).value
      val includes = (autoImport.scaladocExtractorIncludes).value
      val excludes = (autoImport.scaladocExtractorExcludes).value
      val cacheDir = streams.value.cacheDirectory

      if (!autoScalaLibrary.value) {
        log.warn(s"Skip Scaladoc extraction on non-Scala project: ${thisProject.value.id}")
        Seq.empty
      } else {
        val srcFiles = (Compile / sourceDirectories).value.view.flatMap { d =>
          if (!d.exists || !d.isDirectory) {
            List.empty[File]
          } else {
            listFiles(d, includes, excludes)
          }
        }.toSet

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
        Tracked.diffInputs(
          cacheDir / "scaladoc-extractor" / "inputs",
          FilesInfo.lastModified
        )(srcFiles) { changeReport =>
          // Determine which files actually changed
          val existingManaged = (mdir ** "*.scala").get()
          val changedFiles = {
            val changed = changeReport.modified ++ changeReport.added

            if (changed.nonEmpty || managedSourcesAvailable(existingManaged)) {
              changed
            } else {
              log.info("Managed Scaladoc snippets missing, regenerating all source file(s)")
              srcFiles
            }
          }

          val removedFiles = changeReport.removed

          // Clean up generated files from removed source inputs
          val removedManagedFiles = if (removedFiles.nonEmpty) {
            log.info(s"Cleaning up generated files for ${removedFiles.size} removed source file(s)")

            removedFiles.toSeq.flatMap { removedFile =>
              val sourceDir = (Compile / sourceDirectories).value.find { sd =>
                removedFile.toPath.normalize().startsWith(sd.toPath.normalize())
              }

              sourceDir.toSeq.flatMap { sd =>
                val relativePath = sd.toPath.normalize().relativize(removedFile.toPath.normalize())
                val outputName = relativePath.getFileName.toString.dropRight(6)
                val managedPath = {
                  val parent = relativePath.getParent

                  if (parent == null) mdir
                  else mdir.toPath.resolve(parent).toFile
                }
                val stalePattern = (managedPath ** s"scaladoc-${outputName}-*.scala").get()

                stalePattern.foreach { f =>
                  log.debug(s"Deleting stale generated file: ${f.getName}")

                  IO.delete(f)
                }

                stalePattern
              }
            }
          } else {
            Seq.empty[File]
          }

          if (changedFiles.nonEmpty) {
            log.info(s"Extracting snippets from ${changedFiles.size} changed source file(s)")

            changedFiles.toSeq.flatMap { f =>
              // Find the source directory this file belongs to
              val sourceDir = (Compile / sourceDirectories).value.find { sd =>
                f.toPath.normalize().startsWith(sd.toPath.normalize())
              }
              
              sourceDir match {
                case Some(sd) =>
                  val pth = sd.toPath.normalize().relativize(f.toPath.normalize())
                  val content = scala.io.Source.fromFile(f).getLines()

                  if (!content.hasNext) {
                    List.empty
                  } else {
                    log.info(s"Extracting Scaladoc examples from $f ...")

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

                    val debug: String => Unit = msg => log.debug(msg)
                    val nameToWriter: String => (String => Unit) = filename => {
                      fileLineWriter(outDir / filename)
                    }
                    val outputName = pth.getFileName.toString.dropRight(6)
                    val sourcePath = pth.toString

                    parse[JFile](
                      stok,
                      debug,
                      nameToWriter,
                      outputName,
                      sourcePath,
                      1L,
                      content.next(),
                      content,
                      (n, acc) => (new JFile(outDir, n)) :: acc,
                      List.empty[JFile])
                  }
                  
                case None =>
                  log.warn(s"Skipping $f - not in any source directory")
                  List.empty
              }
            }
          } else {
            // Return existing generated files
            if (removedManagedFiles.isEmpty) existingManaged
            else (mdir ** "*.scala").get()
          }
        }
      }
    }).taskValue)

  // ---

  private[cchantep] def listFiles(
    dir: File,
    includeFilter: FileFilter,
    excludeFilter: FileFilter
  ): Seq[File] = {
    val excludes = excludeFilter.accept(_)
    val iofilter = new IOFileFilter {
      def accept(f: File) = includeFilter.accept(f)
      def accept(d: File, n: String) = !excludes(d) && accept(d / n)
    }
    val dirfilter = new IOFileFilter {
      def accept(f: File) = !excludes(f)
      def accept(d: File, n: String) = accept(d / n)
    }

    FileUtils.listFiles(dir, iofilter, dirfilter).
      asScala.filterNot(excludes).toSeq
  }

  private[cchantep] def managedSourcesAvailable(files: Seq[File]): Boolean =
    files.exists(f => f.isFile && f.length > 0L)

  private[cchantep] def fileLineWriter(file: File): String => Unit = {
    IO.write(file, "")

    (str: String) => IO.append(file, s"$str${System.lineSeparator}")
  }

  private val tripleQuote = "\"\"\""

  /**
   * Recursively parses a Scala source file to extract Scaladoc code snippets and generate corresponding files.
   *
   * @param skipTok the token indicating code examples to skip
   * @param log the logger for reporting progress and issues
   * @param dir the output directory for generated files
   * @param source the relative path of the source file
   * @param line the current line number in the source
   * @param head the current line content
   * @param tail the iterator over remaining lines
   * @param generated the list of files generated so far
   * @return the list of all generated files
   */
  @annotation.tailrec
  private[cchantep] def parse[T](
    skipTok: String,
    debug: String => Unit,
    nameToWriter: String => (String => Unit),
    outputName: String,
    sourcePath: String,
    line: Long,
    head: String,
    tail: Iterator[String],
    acc: (String, List[T]) => List[T],
    generated: List[T]
  ): List[T] = {
    val q1 = head.indexOf(tripleQuote)
    val q2 = head.indexOf("\"")
    val si = head.indexOf("/**")

    if (q1 >= 0 && (si < 0 || q1 < si)) {
      parseString(
        skipTok, line,
        tripleQuote, head.drop(q1 + tripleQuote.size), tail) match {
          case Some((ln, hd, tl)) =>
            parse(skipTok, debug, nameToWriter, outputName, sourcePath, ln, hd, tl, acc, generated)
          case None =>
            generated
        }

    } else if (q2 >= 0 && (si < 0 || q2 < si)) {
      parseString(
        skipTok, line,
        "\"", head.drop(q2 + 1), tail) match {
          case Some((ln, hd, tl)) =>
            parse(skipTok, debug, nameToWriter, outputName, sourcePath, ln, hd, tl, acc, generated)
          case None =>
            generated
        }

    } else if (si >= 0) {
      parseScaladoc(
        skipTok, debug, nameToWriter, outputName, sourcePath, line, head.drop(si + 3), tail, acc, generated)

    } else if (tail.hasNext) {
      parse(skipTok, debug, nameToWriter, outputName, sourcePath, line + 1L, tail.next(), tail, acc, generated)
    } else {
      generated
    }
  }

  /**
   * Parses a string segment in a Scala source file, searching for a delimiter and advancing the iterator.
   *
   * @param skipTok token indicating code examples to skip
   * @param line current line number in the source
   * @param delimiter string delimiter to search for
   * @param head current line content
   * @param tail iterator over remaining lines
   * @tparam T type of generated item
   * @return updated list of generated items
   */
  private[cchantep] def parseString[T](
    skipTok: String,
    line: Long,
    delimiter: String,
    head: String,
    tail: Iterator[String]
  ): Option[(Long, String, Iterator[String])] = {
    val i = head.indexOf(delimiter)

    if (i >= 0) {
      Some((line, head.drop(i + delimiter.size), tail))
    } else if (tail.hasNext) {
      Some((line + 1L, tail.next(), tail))
    } else {
      None
    }
  }

  @annotation.tailrec
  private def parseScaladoc[T](
    skipTok: String,
    debug: String => Unit,
    nameToWriter: String => (String => Unit),
    outputName: String,
    sourcePath: String,
    line: Long,
    head: String,
    tail: Iterator[String],
    acc: (/* snippetFileName */ String, List[T]) => List[T],
    generated: List[T]): List[T] = {

    val e = head.indexOf("*/")
    val s = head.indexOf("{{{")

    if (e >= 0 && (s < 0 || e < s)) {
      parse[T](skipTok, debug, nameToWriter, outputName, sourcePath, line, head.drop(e + 2), tail, acc, generated)
    } else if (s > 0) {
      val snippetFileName = s"scaladoc-${outputName}-${line}.scala"
      
      debug(s"Snippet found at line $line (${sourcePath}.scala)")
      
      val writer = nameToWriter(snippetFileName)
      
      writer("package scaladocextractor\r\n")
      writer(s"/* ${sourcePath}, ln $line */")
      writer(s"object ${outputName}${line}Snippet {")
      
      parseSnippetState(
        skipTok, debug, outputName, sourcePath, line,
        writer, head.drop(s + 3), tail) match {
          case Some((ln, hd, tl, complete)) =>
            val updated =
              if (complete) acc(snippetFileName, generated)
              else generated

            parseScaladoc[T](skipTok, debug, nameToWriter, outputName, sourcePath, ln, hd, tl, acc, updated)
          case None =>
            generated
        }
      
      //acc(snippetFileName, updated)
    } else if (!tail.hasNext) {
      generated
    } else {
      parseScaladoc(
        skipTok, debug, nameToWriter, outputName, sourcePath, line + 1L, tail.next(), tail, acc, generated)
    }
  }
  
  /**
   * Recursively parses a Scaladoc code snippet, writing its contents and handling skip tokens, snippet boundaries, and premature ends.
   *
   * @param skipTok Token indicating the snippet should be skipped (e.g., "// not compilable").
   * @param debug Function to log debug messages.
   * @param outputName Name of the output snippet (used for debug/logging).
   * @param sourcePath Relative path of the source file.
   * @param line Current line number in the source file.
   * @param writer Function to write snippet lines to the output.
   * @param head Current line content to parse.
   * @param tail Iterator over remaining lines in the source file.
   * @tparam T Type parameter (not used directly, for compatibility with recursive parsing).
   * @return Option containing (next line number, next head, next tail) if parsing continues, or None if snippet parsing ends.
   */
  private[cchantep] def parseSnippet[T](
    skipTok: String,
    debug: String => Unit,
    outputName: String,
    sourcePath: String,
    line: Long,
    writer: String => Unit,
    head: String,
    tail: Iterator[String]
  ): Option[(Long, String, Iterator[String])] = {
    parseSnippetState[T](
      skipTok, debug, outputName, sourcePath, line, writer, head, tail).
      map { case (ln, hd, tl, _) => (ln, hd, tl) }
  }

  @annotation.tailrec
  private[cchantep] def parseSnippetState[T](
    skipTok: String,
    debug: String => Unit,
    outputName: String,
    sourcePath: String,
    line: Long,
    writer: String => Unit,
    head: String,
    tail: Iterator[String]
  ): Option[(Long, String, Iterator[String], Boolean)] = {
    val s = head.indexOf(skipTok)
    val e = head.indexOf("}}}")
    val i = head.indexOf("*/")

    if (s >= 0 && (e < 0 || s < e) && (i < 0 || s < i)) {
      debug(s"Skip Scaladoc snippet at line $line ($sourcePath): ${cleanSnippet(head)}")

      Some((line, head.drop(s + skipTok.size), tail, false))
    } else if (i >= 0 && (e < 0 || i < e)) {
      val msg = s"Premature end of Scaladoc snippet at line $line (${sourcePath})"
      
      debug(msg)
      
      writer(s"// $msg")
      
      Some((line, head.drop(i + 2), tail, false))
    } else if (e >= 0) {
      val (code, rem) = head.splitAt(e)
      val normalized = cleanSnippet(code)
      if (normalized.trim.nonEmpty) writer(s"  $normalized") else writer(normalized)
      writer("}" /* enclosing snippet object */ )
      debug(s"Generating ${outputName}")
      Some((line, rem.drop(3), tail, true))
    } else {
      val normalized = cleanSnippet(head)
      if (normalized.trim.nonEmpty) writer(s"  $normalized") else writer(normalized)
      if (tail.hasNext) {
        parseSnippetState[T](
          skipTok, debug, outputName, sourcePath, line + 1L,
          writer, tail.next(), tail)
      } else {
        None
      }
    }
  }

  private[cchantep] def cleanSnippet(in: String): String ="^[ \\t]*\\*[ ]?".r.replaceFirstIn(in, "")
}
