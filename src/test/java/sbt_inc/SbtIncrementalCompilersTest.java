/*
 * This is free and unencumbered software released into the public domain.
 * See UNLICENSE.
 */
package sbt_inc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import sbt_inc.SbtIncrementalCompilers.CompilersCacheKey;

/**
 * Guards the cache key that lets {@link SbtIncrementalCompilers} reuse a compiler across modules.
 * Equal keys mean two modules share the cached compiler (the performance win); unequal keys mean
 * modules with different Scala toolchains never reuse each other's compiler.
 */
public class SbtIncrementalCompilersTest {

  private static final File JAVA_HOME = new File("/opt/jdk");
  private static final File COMPILER = new File("/repo/scala-compiler-2.13.16.jar");
  private static final File LIBRARY = new File("/repo/scala-library-2.13.16.jar");

  private static CompilersCacheKey key(
      String scalaVersion, File compiler, File library, File javaHome) {
    return new CompilersCacheKey(
        scalaVersion,
        Collections.singletonList(compiler),
        Collections.singletonList(library),
        javaHome);
  }

  @Test
  public void sameConfigurationProducesEqualKeys() {
    CompilersCacheKey a = key("2.13.16", COMPILER, LIBRARY, JAVA_HOME);
    CompilersCacheKey b = key("2.13.16", COMPILER, LIBRARY, JAVA_HOME);
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  public void jarOrderDoesNotAffectKey() {
    File j1 = new File("/repo/dep-a.jar");
    File j2 = new File("/repo/dep-b.jar");
    CompilersCacheKey a =
        new CompilersCacheKey(
            "2.13.16", Arrays.asList(j1, j2), Collections.singletonList(LIBRARY), JAVA_HOME);
    CompilersCacheKey b =
        new CompilersCacheKey(
            "2.13.16", Arrays.asList(j2, j1), Collections.singletonList(LIBRARY), JAVA_HOME);
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  public void differentScalaVersionProducesDifferentKey() {
    assertNotEquals(
        key("2.12.20", COMPILER, LIBRARY, JAVA_HOME), key("2.13.16", COMPILER, LIBRARY, JAVA_HOME));
  }

  @Test
  public void differentCompilerJarsProduceDifferentKey() {
    assertNotEquals(
        key("2.13.16", COMPILER, LIBRARY, JAVA_HOME),
        key("2.13.16", new File("/repo/other-compiler.jar"), LIBRARY, JAVA_HOME));
  }

  @Test
  public void differentJavaHomeProducesDifferentKey() {
    assertNotEquals(
        key("2.13.16", COMPILER, LIBRARY, JAVA_HOME),
        key("2.13.16", COMPILER, LIBRARY, new File("/opt/other-jdk")));
  }
}
