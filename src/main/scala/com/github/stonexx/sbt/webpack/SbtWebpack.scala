package com.github.stonexx.sbt.webpack

import java.io.IOException
import java.net.URLEncoder

import com.typesafe.jse.LocalEngine
import com.typesafe.sbt.jse.SbtJsTask
import com.typesafe.sbt.web.SbtWeb
import com.typesafe.sbt.web.SbtWeb.autoImport.{WebKeys, _}
import org.apache.commons.compress.utils.CharsetNames.UTF_8
import org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS
import sbt._
import sbt.Keys._
import sbt.util.CacheStore
import spray.json.{DefaultJsonProtocol, JsBoolean, JsObject, JsValue, JsonParser}

import scala.collection.{immutable, mutable}
import scala.language.implicitConversions
import scala.sys.process.{BasicIO, Process, ProcessIO}

object SbtWebpack extends AutoPlugin {
  override def requires = SbtJsTask

  override def trigger = AllRequirements

  object autoImport {
    val webpack: InputKey[Unit] = inputKey[Unit]("Webpack module bundler.")

    object WebpackModes {
      val Dev: Configuration = config("dev")
      val Prod: Configuration = config("prod")
      val Test: Configuration = config("test")
    }

    object WebpackKeys {
      val config: SettingKey[File] = SettingKey[File]("webpackConfig", "The location of a webpack configuration file.")
      val envVars: SettingKey[Map[String, String]] = SettingKey[Map[String, String]]("webpackEnvVars", "Environment variable names and values to set for webpack.")
    }

  }

  import autoImport._
  import autoImport.WebpackKeys._

  private val webpackDependTasks: Seq[Scoped.AnyInitTask] = Seq(
    WebKeys.nodeModules in Plugin,
    WebKeys.nodeModules in Assets,
    WebKeys.webModules in Assets
  )

  override def projectSettings: Seq[Setting[_]] = Seq(
    includeFilter in webpack := AllPassFilter,
    includeFilter in(WebpackModes.Dev, webpack) := (includeFilter in webpack).value,
    includeFilter in(WebpackModes.Prod, webpack) := (includeFilter in webpack).value,
    includeFilter in(WebpackModes.Test, webpack) := (includeFilter in webpack).value,

    excludeFilter in webpack := HiddenFileFilter,
    excludeFilter in(WebpackModes.Dev, webpack) := (excludeFilter in webpack).value,
    excludeFilter in(WebpackModes.Prod, webpack) := (excludeFilter in webpack).value,
    excludeFilter in(WebpackModes.Test, webpack) := (excludeFilter in webpack).value,

    config in webpack := baseDirectory.value / "webpack.config.js",
    config in(WebpackModes.Dev, webpack) := (config in webpack).value,
    config in(WebpackModes.Prod, webpack) := (config in webpack).value,
    config in(WebpackModes.Test, webpack) := (config in webpack).value,

    envVars in webpack := LocalEngine.nodePathEnv((WebKeys.nodeModuleDirectories in Plugin).value.map(_.getCanonicalPath).to[immutable.Seq]),
    envVars in(WebpackModes.Dev, webpack) := (envVars in webpack).value + ("NODE_ENV" -> "development"),
    envVars in(WebpackModes.Prod, webpack) := (envVars in webpack).value + ("NODE_ENV" -> "production"),
    envVars in(WebpackModes.Test, webpack) := (envVars in webpack).value + ("NODE_ENV" -> "testing"),

    webpack := Def.inputTaskDyn {
      import complete.DefaultParsers._

      val arg = (EOF | Seq(
        "dev",
        "prod",
        "test"
      ).map(t => Space ~ token(t)).reduce(_ | _).map(_._2)).parsed

      val cacheDir = streams.value.cacheDirectory

      arg match {
        case "dev" => Def.taskDyn(runWebpack(cacheDir, WebpackModes.Dev).dependsOn(webpackDependTasks: _*))
        case "prod" | () => Def.taskDyn(runWebpack(cacheDir, WebpackModes.Prod).dependsOn(webpackDependTasks: _*))
        case "test" => Def.taskDyn(runWebpack(cacheDir, WebpackModes.Test).dependsOn(webpackDependTasks: _*))
      }
    }.evaluated
  )

  case class NodeMissingException(cause: Throwable) extends RuntimeException("'node' is required. Please install it and add it to your PATH.", cause)

  case class NodeExecuteFailureException(exitValue: Int) extends RuntimeException("Failed to execute node.")

  private def getWebpackScript(cacheDir: File): Def.Initialize[Task[File]] = Def.task {
    SbtWeb.copyResourceTo(
      (target in Plugin).value / webpack.key.label,
      getClass.getClassLoader.getResource("webpack.js"),
      cacheDir / "get-webpack-script"
    )
  }

  private def getContexts(cacheDir: File, mode: Configuration): Def.Initialize[Task[Seq[File]]] = Def.task {
    val getContextsScript = SbtWeb.copyResourceTo(
      (target in Plugin).value / webpack.key.label,
      getClass.getClassLoader.getResource("contexts.js"),
      cacheDir / "get-contexts-script"
    )
    val results = runNode(
      baseDirectory.value,
      getContextsScript,
      List((config in(mode, webpack)).value.absolutePath),
      (envVars in(mode, webpack)).value,
      state.value.log
    )
    import DefaultJsonProtocol._
    results.headOption.toList.flatMap(_.convertTo[Seq[String]]).map(path => new File(path))
  }

  private def relativizedPath(base: File, file: File): String =
    Path.relativeTo(base)(file).getOrElse(file.absolutePath)

  private def cached(cacheBaseDirectory: File, inStyle: FileInfo.Style)(action: Set[File] => Unit): Set[File] => Unit = {
    import Path._
    lazy val inCache = Difference.inputs(CacheStore(cacheBaseDirectory / "in-cache"), inStyle)
    inputs => {
      inCache(inputs) { inReport =>
        if (inReport.modified.nonEmpty) action(inReport.modified)
      }
    }
  }

  private def runWebpack(cacheDir: File, mode: Configuration): Def.Initialize[Task[Unit]] = Def.task {
    Seq(
      WebpackModes.Dev,
      WebpackModes.Prod,
      WebpackModes.Test
    ).filter(_ != mode).foreach { m =>
      IO.delete(cacheDir / "run" / m.name)
    }

    val stateValue = state.value
    val webpackScript = getWebpackScript(cacheDir).value

    val runCacheDir = cacheDir / "run" / mode.name
    val runUpdate = cached(runCacheDir, FilesInfo.hash) { _ =>

      val relativePath = relativizedPath(baseDirectory.value, (config in(mode, webpack)).value)
      stateValue.log.info(s"Running ${mode.name} by ${relativePath}")

      runNode(
        baseDirectory.value,
        webpackScript,
        List(
          (config in(mode, webpack)).value.absolutePath,
          URLEncoder.encode(JsObject("watch" -> JsBoolean(false)).toString, UTF_8)
        ),
        (envVars in(mode, webpack)).value,
        stateValue.log
      )

      doClean(runCacheDir.getParentFile.*(DirectoryFilter).get, Seq(runCacheDir))
    }

    val include = (includeFilter in(mode, webpack)).value
    val exclude = (excludeFilter in(mode, webpack)).value
    val inputFiles = getContexts(cacheDir, mode).value.flatMap(_.**(include && -exclude).get).filterNot(_.isDirectory)

    runUpdate(((config in(mode, webpack)).value +: inputFiles).toSet)
  }

  def doClean(clean: Seq[File], preserve: Seq[File]): Unit = {
    IO.withTemporaryDirectory { temp =>
      val (dirs, files) = preserve.filter(_.exists).partition(_.isDirectory)
      val mappings = files.zipWithIndex map { case (f, i) => (f, new File(temp, i.toHexString)) }
      IO.move(mappings)
      IO.delete(clean)
      IO.createDirectories(dirs) // recreate empty directories
      IO.move(mappings.map(_.swap))
    }
  }

  private def runNode(base: File, script: File, args: List[String], env: Map[String, String], log: Logger): Seq[JsValue] = {
    val resultBuffer = mutable.ArrayBuffer.newBuilder[JsValue]
    val exitValue = try {
      fork(
        "node" :: script.absolutePath :: args,
        base, env,
        log.info(_),
        log.error(_),
        line => resultBuffer += JsonParser(line)
      ).exitValue()
    } catch {
      case e: IOException => throw NodeMissingException(e)
    }
    if (exitValue != 0) {
      throw NodeExecuteFailureException(exitValue)
    }
    resultBuffer.result()
  }

  private val ResultEscapeChar: Char = 0x10

  private def fork(
                    command: List[String], base: File, env: Map[String, String],
                    processOutput: (String => Unit),
                    processError: (String => Unit),
                    processResult: (String => Unit)
                  ): Process = {
    val io = new ProcessIO(
      writeInput = BasicIO.input(false),
      processOutput = BasicIO.processFully { line =>
        if (line.indexOf(ResultEscapeChar) == -1) {
          processOutput(line)
        } else {
          val (out, result) = line.span(_ != ResultEscapeChar)
          if (!out.isEmpty) {
            processOutput(out)
          }
          processResult(result.drop(1))
        }
      },
      processError = BasicIO.processFully(processError),
      false
    )
    if (IS_OS_WINDOWS)
      Process("cmd" :: "/c" :: command, base, env.toSeq: _*).run(io)
    else
      Process(command, base, env.toSeq: _*).run(io)
  }
}

