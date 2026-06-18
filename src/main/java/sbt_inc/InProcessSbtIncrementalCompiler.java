/*
 * This is free and unencumbered software released into the public domain.
 * See UNLICENSE.
 */
package sbt_inc;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import sbt.internal.inc.*;
import sbt.internal.inc.FileAnalysisStore;
import xsbti.PathBasedFile;
import xsbti.T2;
import xsbti.VirtualFile;
import xsbti.compile.*;

/**
 * In-process incremental compiler. The {@link Compilers} instance (which holds the Scala compiler
 * class loaders) is expensive to build and is shared/reused across modules and goals via {@link
 * SbtIncrementalCompilers}. Everything that depends on the per-compilation analysis cache file (the
 * {@link Setup} and {@link AnalysisStore}) is created per {@link #compile} call so a single
 * instance can serve both {@code compile} and {@code testCompile}.
 */
public final class InProcessSbtIncrementalCompiler implements SbtIncrementalCompiler {
  private final Compilers compilers;
  private final IncrementalCompiler compiler;
  private final xsbti.Logger sbtLogger;

  public InProcessSbtIncrementalCompiler(
      Compilers compilers, IncrementalCompiler compiler, xsbti.Logger sbtLogger) {
    this.compilers = compilers;
    this.compiler = compiler;
    this.sbtLogger = sbtLogger;
  }

  @Override
  public void compile(
      Collection<File> classpathElements,
      Collection<File> sources,
      File classesDirectory,
      Collection<String> scalacOptions,
      Collection<String> javacOptions,
      CompileOrder compileOrder,
      File cacheFile) {

    AnalysisStore analysisStore = AnalysisStore.getCachedStore(FileAnalysisStore.binary(cacheFile));
    Setup setup = makeSetup(cacheFile, this.sbtLogger);

    // incremental compiler needs to add the output dir in the classpath for Java + Scala
    Collection<File> fullClasspathElements = new ArrayList<>(classpathElements);
    fullClasspathElements.add(classesDirectory);

    CompileOptions options =
        CompileOptions.of(
            fullClasspathElements.stream()
                .map(file -> new PlainVirtualFile(file.toPath()))
                .toArray(VirtualFile[]::new), // classpath
            sources.stream()
                .map(file -> new PlainVirtualFile(file.toPath()))
                .toArray(VirtualFile[]::new), // sources
            classesDirectory.toPath(), //
            scalacOptions.toArray(new String[] {}), // scalacOptions
            javacOptions.toArray(new String[] {}), // javacOptions
            100, // maxErrors
            pos -> pos, // sourcePositionMappers
            compileOrder, // order
            Optional.empty(), // temporaryClassesDirectory
            Optional.empty(), // _converter
            Optional.empty(), // _stamper
            Optional.empty() // _earlyOutput
            );

    Inputs inputs = Inputs.of(this.compilers, options, setup, previousResult(analysisStore));

    CompileResult newResult = this.compiler.compile(inputs, this.sbtLogger);
    analysisStore.set(AnalysisContents.create(newResult.analysis(), newResult.setup()));
  }

  private static PreviousResult previousResult(AnalysisStore analysisStore) {
    Optional<AnalysisContents> analysisContents = analysisStore.get();
    if (analysisContents.isPresent()) {
      AnalysisContents analysisContents0 = analysisContents.get();
      CompileAnalysis previousAnalysis = analysisContents0.getAnalysis();
      MiniSetup previousSetup = analysisContents0.getMiniSetup();
      return PreviousResult.of(Optional.of(previousAnalysis), Optional.of(previousSetup));
    } else {
      return PreviousResult.of(Optional.empty(), Optional.empty());
    }
  }

  private static Setup makeSetup(File cacheFile, xsbti.Logger sbtLogger) {
    PerClasspathEntryLookup lookup =
        new PerClasspathEntryLookup() {
          @Override
          public Optional<CompileAnalysis> analysis(VirtualFile classpathEntry) {
            Path path = ((PathBasedFile) classpathEntry).toPath();

            String analysisStoreFileName = null;
            if (Files.isDirectory(path)) {
              String fileName = path.getFileName().toString();
              if (fileName.equals("classes")) {
                analysisStoreFileName = "compile";

              } else if (fileName.equals("test-classes")) {
                analysisStoreFileName = "test-compile";
              }
            }

            if (analysisStoreFileName != null) {
              File analysisStoreFile =
                  path.getParent().resolve("analysis").resolve(analysisStoreFileName).toFile();
              if (analysisStoreFile.exists()) {
                return AnalysisStore.getCachedStore(FileAnalysisStore.binary(analysisStoreFile))
                    .get()
                    .map(AnalysisContents::getAnalysis);
              }
            }
            return Optional.empty();
          }

          @Override
          public DefinesClass definesClass(VirtualFile classpathEntry) {
            return classpathEntry.name().equals("rt.jar")
                ? className -> false
                : Locate.definesClass(classpathEntry);
          }
        };

    return Setup.of(
        lookup, // lookup
        false, // skip
        cacheFile, // cacheFile
        CompilerCache.fresh(), // cache
        IncOptions.of(), // incOptions
        new LoggedReporter(100, sbtLogger, pos -> pos), // reporter
        Optional.empty(), // optionProgress
        new T2[] {});
  }
}
