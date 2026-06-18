/*
 * This is free and unencumbered software released into the public domain.
 * See UNLICENSE.
 */
package sbt_inc;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.apache.commons.exec.LogOutputStream;
import org.apache.maven.plugin.logging.Log;
import sbt.internal.inc.*;
import sbt.internal.inc.ScalaInstance;
import sbt.internal.inc.classpath.ClassLoaderCache;
import sbt.util.Logger;
import scala.Option;
import scala.jdk.FunctionWrappers;
import scala_maven.MavenArtifactResolver;
import scala_maven.VersionNumber;
import scala_maven_executions.Fork;
import scala_maven_executions.ForkLogger;
import xsbti.compile.*;

public final class SbtIncrementalCompilers {

  /**
   * Cache of the expensive, reusable {@link Compilers} object (which holds the Scala compiler class
   * loaders) keyed by the full compiler identity. Maven caches a plugin's class loader realm for
   * the whole reactor, so this static field is shared across every module and goal of a single
   * build, which is what lets us reuse the compiler. The key includes the Scala version, the
   * compiler and library jar sets and the Java home so that modules using different Scala
   * toolchains never reuse each other's compiler. {@link ConcurrentHashMap} makes it safe for
   * parallel ({@code -T}) builds.
   */
  private static final ConcurrentHashMap<CompilersCacheKey, Compilers> COMPILERS_CACHE =
      new ConcurrentHashMap<>();

  public static SbtIncrementalCompiler make(
      File javaHome,
      MavenArtifactResolver resolver,
      File secondaryCacheDir,
      Log mavenLogger,
      VersionNumber scalaVersion,
      Collection<File> compilerAndDependencies,
      Collection<File> libraryAndDependencies,
      String[] jvmArgs,
      File javaExec,
      List<File> forkBootClasspath)
      throws Exception {

    if (jvmArgs == null || jvmArgs.length == 0) {
      // In-process: reuse the (cached) Compilers across modules/goals.
      CompilersCacheKey key =
          new CompilersCacheKey(
              scalaVersion.toString(), compilerAndDependencies, libraryAndDependencies, javaHome);
      Compilers compilers =
          getOrBuildCompilers(
              key,
              javaHome,
              resolver,
              secondaryCacheDir,
              mavenLogger,
              scalaVersion,
              compilerAndDependencies,
              libraryAndDependencies);
      return new InProcessSbtIncrementalCompiler(
          compilers, ZincUtil.defaultIncrementalCompiler(), new MavenLoggerSbtAdapter(mavenLogger));
    } else {
      // Forked: each compile runs in a fresh JVM, so in-process reuse cannot help; only the
      // compiled
      // bridge jar (already disk-cached) is needed in the parent.
      ScalaInstance scalaInstance =
          ScalaInstances.makeScalaInstance(
              scalaVersion.toString(), compilerAndDependencies, libraryAndDependencies);
      File compilerBridgeJar =
          CompilerBridgeFactory.getCompiledBridgeJar(
              scalaVersion, scalaInstance, secondaryCacheDir, resolver, mavenLogger);
      return makeForkedProcess(
          javaHome,
          compilerBridgeJar,
          scalaVersion,
          compilerAndDependencies,
          libraryAndDependencies,
          mavenLogger,
          jvmArgs,
          javaExec,
          forkBootClasspath);
    }
  }

  /**
   * Used by {@link ForkedSbtIncrementalCompilerMain} inside the forked JVM (single use, uncached).
   */
  static SbtIncrementalCompiler makeInProcess(
      File javaHome, ScalaInstance scalaInstance, File compilerBridgeJar, Logger sbtLogger) {
    Compilers compilers = makeCompilers(scalaInstance, javaHome, compilerBridgeJar);
    return new InProcessSbtIncrementalCompiler(
        compilers, ZincUtil.defaultIncrementalCompiler(), sbtLogger);
  }

  private static Compilers getOrBuildCompilers(
      CompilersCacheKey key,
      File javaHome,
      MavenArtifactResolver resolver,
      File secondaryCacheDir,
      Log mavenLogger,
      VersionNumber scalaVersion,
      Collection<File> compilerAndDependencies,
      Collection<File> libraryAndDependencies) {
    return COMPILERS_CACHE.computeIfAbsent(
        key,
        k -> {
          ScalaInstance scalaInstance =
              ScalaInstances.makeScalaInstance(
                  scalaVersion.toString(), compilerAndDependencies, libraryAndDependencies);
          try {
            File compilerBridgeJar =
                CompilerBridgeFactory.getCompiledBridgeJar(
                    scalaVersion, scalaInstance, secondaryCacheDir, resolver, mavenLogger);
            return makeCompilers(scalaInstance, javaHome, compilerBridgeJar);
          } catch (Exception e) {
            throw new RuntimeException("Failed to build the Scala incremental compiler", e);
          }
        });
  }

  private static SbtIncrementalCompiler makeForkedProcess(
      File javaHome,
      File compilerBridgeJar,
      VersionNumber scalaVersion,
      Collection<File> compilerAndDependencies,
      Collection<File> libraryAndDependencies,
      Log mavenLogger,
      String[] jvmArgs,
      File javaExec,
      List<File> pluginArtifacts) {

    List<String> forkClasspath =
        pluginArtifacts.stream().map(File::getPath).collect(Collectors.toList());

    return (classpathElements,
        sources,
        classesDirectory,
        scalacOptions,
        javacOptions,
        compileOrder,
        cacheFile) -> {
      try {
        String[] args =
            new ForkedSbtIncrementalCompilerMain.Args(
                    javaHome,
                    cacheFile,
                    compileOrder,
                    compilerBridgeJar,
                    scalaVersion.toString(),
                    compilerAndDependencies,
                    libraryAndDependencies,
                    classpathElements,
                    sources,
                    classesDirectory,
                    scalacOptions,
                    javacOptions,
                    mavenLogger.isDebugEnabled())
                .generateArgs();

        Fork fork =
            new Fork(
                ForkedSbtIncrementalCompilerMain.class.getName(),
                forkClasspath,
                jvmArgs,
                args,
                javaExec);

        fork.run(
            new LogOutputStream() {
              private final ForkLogger forkLogger =
                  new ForkLogger() {
                    @Override
                    public void onException(Exception t) {
                      mavenLogger.error(t);
                    }

                    @Override
                    public void onError(String content) {
                      mavenLogger.error(content);
                    }

                    @Override
                    public void onWarn(String content) {
                      mavenLogger.warn(content);
                    }

                    @Override
                    public void onInfo(String content) {
                      mavenLogger.info(content);
                    }

                    @Override
                    public void onDebug(String content) {
                      mavenLogger.debug(content);
                    }
                  };

              @Override
              protected void processLine(String line, int level) {
                forkLogger.processLine(line);
              }

              public void close() throws IOException {
                forkLogger.forceNextLineToFlush();
                super.close();
              }
            });
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    };
  }

  private static Compilers makeCompilers(
      ScalaInstance scalaInstance, File javaHome, File compilerBridgeJar) {
    ClassLoaderCache classLoaderCache =
        new ClassLoaderCache(SbtIncrementalCompilers.class.getClassLoader());

    ScalaCompiler scalaCompiler =
        new AnalyzingCompiler(
            scalaInstance, // scalaInstance
            ZincCompilerUtil.constantBridgeProvider(scalaInstance, compilerBridgeJar), // provider
            ClasspathOptionsUtil.auto(), // classpathOptions
            new FunctionWrappers.FromJavaConsumer<>(noop -> {}), // onArgsHandler
            Option.apply(classLoaderCache) // classLoaderCache
            );

    return ZincUtil.compilers(
        scalaInstance, ClasspathOptionsUtil.boot(), Option.apply(javaHome.toPath()), scalaCompiler);
  }

  /**
   * Identity of a reusable {@link Compilers}: anything that changes the Scala compiler we build.
   */
  static final class CompilersCacheKey {
    private final String scalaVersion;
    private final List<String> compilerJars;
    private final List<String> libraryJars;
    private final String javaHome;

    CompilersCacheKey(
        String scalaVersion,
        Collection<File> compilerAndDependencies,
        Collection<File> libraryAndDependencies,
        File javaHome) {
      this.scalaVersion = scalaVersion;
      this.compilerJars = sortedPaths(compilerAndDependencies);
      this.libraryJars = sortedPaths(libraryAndDependencies);
      this.javaHome = javaHome == null ? null : javaHome.getAbsolutePath();
    }

    private static List<String> sortedPaths(Collection<File> files) {
      return files.stream().map(File::getAbsolutePath).sorted().collect(Collectors.toList());
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof CompilersCacheKey)) {
        return false;
      }
      CompilersCacheKey that = (CompilersCacheKey) o;
      return Objects.equals(scalaVersion, that.scalaVersion)
          && compilerJars.equals(that.compilerJars)
          && libraryJars.equals(that.libraryJars)
          && Objects.equals(javaHome, that.javaHome);
    }

    @Override
    public int hashCode() {
      return Objects.hash(scalaVersion, compilerJars, libraryJars, javaHome);
    }
  }
}
