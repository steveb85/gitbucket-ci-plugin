package io.github.gitbucket.ci.util

import java.io.File
import java.util.concurrent.{ExecutorService, Executors, ThreadFactory, TimeUnit}

import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory

import scala.util.control.NonFatal

/**
 * Deletes build directories off the build thread, one task at a time.
 *
 * A workspace is thousands of small files on the shared volume. Deleting it synchronously
 * kept the build marked as running for minutes after the deploy had already finished.
 */
object BuildCleanup {

  sealed trait Task
  final case class Trim(buildDir: File, label: String) extends Task
  final case class Delete(buildDir: File, label: String) extends Task

  private val logger = LoggerFactory.getLogger("io.github.gitbucket.ci.util.BuildCleanup")

  private val executor: ExecutorService =
    Executors.newSingleThreadExecutor(new ThreadFactory {
      override def newThread(runnable: Runnable): Thread = {
        val thread = new Thread(runnable, "gitbucket-ci-cleanup")
        thread.setDaemon(true)
        thread
      }
    })

  def submit(tasks: Seq[Task]): Unit = {
    if (tasks.nonEmpty) {
      try {
        executor.submit(new Runnable {
          override def run(): Unit = tasks.foreach(runSafely)
        })
      } catch {
        case NonFatal(e) =>
          logger.warn("Could not queue build directory cleanup; it will be retried after the next build", e)
      }
    }
  }

  private def runSafely(task: Task): Unit = {
    try {
      run(task)
    } catch {
      case NonFatal(e) =>
        logger.warn(s"Build directory cleanup failed for ${labelOf(task)}; it will be retried after the next build", e)
    }
  }

  private def run(task: Task): Unit = {
    val start = System.currentTimeMillis()
    task match {
      case Trim(buildDir, label) =>
        val trimmed = CIUtils.trimBuildDir(buildDir)
        val failed = trimmed.collect { case (file, false) => file.getName }
        if (failed.nonEmpty) {
          logger.warn(
            s"Could not delete ${failed.mkString(", ")} from the build directory of $label; " +
            "will retry after the next build"
          )
        } else if (trimmed.nonEmpty) {
          logger.info(
            s"Trimmed build directory of $label to its log in ${formatDuration(System.currentTimeMillis() - start)}"
          )
        }

      case Delete(buildDir, label) =>
        if (buildDir.exists()) {
          if (FileUtils.deleteQuietly(buildDir)) {
            logger.info(
              s"Deleted build directory of $label in ${formatDuration(System.currentTimeMillis() - start)}"
            )
          } else {
            logger.warn(s"Could not delete the build directory of $label; will retry after the next build")
          }
        }
    }
  }

  private def labelOf(task: Task): String = task match {
    case Trim(_, label)   => label
    case Delete(_, label) => label
  }

  private def formatDuration(millis: Long): String = {
    val seconds = millis / 1000
    if (seconds >= 60) s"${seconds / 60}m ${seconds % 60}s" else s"${seconds}s"
  }

  def shutdown(): Unit = {
    executor.shutdown()
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow()
      }
    } catch {
      case _: InterruptedException =>
        executor.shutdownNow()
        Thread.currentThread().interrupt()
    }
  }
}
