package io.github.gitbucket.ci.util

import java.io.File
import java.nio.file.Files

import org.scalatest.funsuite.AnyFunSuite

class BuildCleanupSpec extends AnyFunSuite {

  test("trim runs asynchronously and keeps the output log") {
    val dir = Files.createTempDirectory("ci-cleanup").toFile
    val output = new File(dir, CIUtils.OutputFileName)
    Files.writeString(output.toPath, "log")

    val workspace = new File(dir, "workspace")
    workspace.mkdirs()
    Files.writeString(new File(workspace, "file.txt").toPath, "data")

    BuildCleanup.submit(Seq(BuildCleanup.Trim(dir, "root/test#1")))

    val deadline = System.currentTimeMillis() + 5000
    while (workspace.exists() && System.currentTimeMillis() < deadline) {
      Thread.sleep(50)
    }

    assert(!workspace.exists(), "the workspace should be removed")
    assert(output.exists(), "the output log must be kept")
  }
}
