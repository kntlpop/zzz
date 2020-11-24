/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.codegen

import org.jetbrains.kotlin.cli.common.output.writeAll
import org.jetbrains.kotlin.codegen.forTestCompile.ForTestCompileRuntime
import org.jetbrains.kotlin.load.kotlin.PackagePartClassUtils
import org.jetbrains.kotlin.test.ConfigurationKind
import org.jetbrains.kotlin.test.KotlinTestUtils
import org.jetbrains.kotlin.test.TestJdkKind
import java.io.File
import java.util.concurrent.TimeUnit

abstract class AbstractCustomJDKBlackBoxCodegenTest : AbstractBlackBoxCodegenTest() {
    @Throws(Exception::class)
    override fun doTest(filePath: String) {
        val file = File(filePath)
        val expectedText =
            KotlinTestUtils.doLoadFile(file) +
                    "\n" +
                    """
                        fun main() {
                            val res = box()
                            if (res != "OK") throw AssertionError(res)
                        }
                    """.trimIndent()
        val testFiles = createTestFilesFromFile(file, expectedText)
        doMultiFileTest(file, testFiles)
    }

    override fun extractConfigurationKind(files: List<TestFile>): ConfigurationKind {
        return ConfigurationKind.NO_KOTLIN_REFLECT
    }

    override fun runJavacTask(files: MutableCollection<File>, options: List<String>) {
        KotlinTestUtils.compileJavaFilesExternally(files, options + getAdditionalJavacArgs(), getJdkHome())
    }

    override fun getTestJdkKind(files: List<TestFile>): TestJdkKind {
        return getTestJdkKind()
    }

    abstract fun getTestJdkKind(): TestJdkKind
    abstract fun getJdkHome(): File

    open fun getAdditionalJavacArgs(): List<String> = emptyList()
    open fun getAdditionalJvmArgs(): List<String> = emptyList()

    abstract override fun getPrefix(): String

    override fun blackBox(reportProblems: Boolean, unexpectedBehaviour: Boolean) {
        val tmpdir = KotlinTestUtils.tmpDirForTest(this)
        val fileFactory = generateClassesInFile()
        fileFactory.writeAll(tmpdir, null)

        val jdkHome = getJdkHome()
        val javaExe = File(jdkHome, "bin/java.exe").takeIf(File::exists)
            ?: File(jdkHome, "bin/java").takeIf(File::exists)
            ?: error("Can't find 'java' executable in $jdkHome")


        val command = arrayOf(
            javaExe.absolutePath,
            "-ea",
            *getAdditionalJvmArgs().toTypedArray(),
            "-classpath",
            listOfNotNull(
                tmpdir, ForTestCompileRuntime.runtimeJarForTests(),
                javaClassesOutputDirectory
            ).joinToString(File.pathSeparator, transform = File::getAbsolutePath),
            getFacadeFqName(myFiles.psiFile)
        )

        val process = ProcessBuilder(*command).inheritIO().start()
        process.waitFor(1, TimeUnit.MINUTES)
        assertEquals(0, process.exitValue())
    }
}