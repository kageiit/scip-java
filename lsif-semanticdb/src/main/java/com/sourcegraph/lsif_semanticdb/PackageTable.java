package com.sourcegraph.lsif_semanticdb;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class PackageTable implements Function<Package, Integer> {

  private final Map<String, Package> byClassfile = new HashMap<>();
  private final Set<String> cachedJdkSymbols = new HashSet<>();
  private final Map<Package, Integer> lsif = new ConcurrentHashMap<>();
  private final JavaVersion javaVersion;
  private final boolean indexJdk;
  private final LsifWriter writer;

  private static final PathMatcher JAR_PATTERN =
      FileSystems.getDefault().getPathMatcher("glob:**.jar");
  private static final PathMatcher CLASS_PATTERN =
      FileSystems.getDefault().getPathMatcher("glob:**.class");

  public PackageTable(LsifSemanticdbOptions options, LsifWriter writer) throws IOException {
    this.writer = writer;
    this.javaVersion = new JavaVersion();
    this.indexJdk = options.indexJdk;
    for (MavenPackage pkg : options.packages) {
      indexPackage(pkg);
    }
    if (indexJdk) {
      indexJdk();
    }
  }

  public void writeImportedSymbol(String symbol, int monikerId) {
    packageForSymbol(symbol)
        .ifPresent(
            pkg -> {
              int pkgId = lsif.computeIfAbsent(pkg, this);
              writer.emitPackageInformationEdge(monikerId, pkgId);
            });
  }

  private Optional<Package> packageForClassfile(String classfile) {
    Package result = byClassfile.get(classfile);
    if (result != null) return Optional.of(result);
    if (!javaVersion.isJava8 && isJrtClassfile(classfile)) return Optional.of(javaVersion.pkg);
    return Optional.empty();
  }

  private Optional<Package> packageForSymbol(String symbol) {
    return SymbolDescriptor.toplevel(symbol)
        .flatMap(
            toplevel -> {
              String classfile = toplevel.owner + toplevel.descriptor.name + ".class";
              return packageForClassfile(classfile);
            });
  }

  private void indexPackage(MavenPackage pkg) throws IOException {
    if (!JAR_PATTERN.matches(pkg.jar)) {
      return;
    }
    if (!Files.isRegularFile(pkg.jar)) {
      return;
    }
    indexJarFile(pkg.jar, pkg);
  }

  private void indexJarFile(Path file, Package pkg) throws IOException {
    try (JarFile jar = new JarFile(file.toFile())) {
      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        if (entry.getName().endsWith(".class") && !entry.getName().contains("$")) {
          byClassfile.put(entry.getName(), pkg);
        }
      }
    }
  }

  private void indexJdk() throws IOException {
    if (javaVersion.isJava8) {
      indexBootstrapClasspath();
    }
  }

  /**
   * The JRT classpath contains classfiles for the JDK for Java versions 9+.
   *
   * <p>The JRT classpath isn't a jar file on disk, it needs to be read from an internal file system
   * under the URL "jrt:/".
   */
  private boolean isJrtClassfile(String classfile) {
    if (!indexJdk) return false;
    if (cachedJdkSymbols.contains(classfile)) return true;
    URL resource = getClass().getResource("/" + classfile);
    boolean isJrt = resource != null && "jrt".equals(resource.getProtocol());
    if (isJrt) {
      cachedJdkSymbols.add(classfile);
    }
    return isJrt;
  }

  /**
   * Returns the equivalent of `Path.toString()` but uses forward slashes on all operating systems
   * (including Windows).
   */
  private String relativePathToString(Path path) {
    StringBuilder out = new StringBuilder();
    Iterator<Path> it = path.iterator();
    boolean first = true;
    while (it.hasNext()) {
      if (first) {
        first = false;
      } else {
        out.append('/');
      }
      String filename = it.next().toString();
      out.append(filename);
    }
    return out.toString();
  }

  /**
   * The boot classpath contains jar files for the JDK in Java 8.
   *
   * <p>The bootclasspath is normal jar files on disk that can live under $JAVA_HOME.
   */
  private void indexBootstrapClasspath() throws IOException {
    for (Object keyObject : System.getProperties().keySet()) {
      Package jdk = new JdkPackage("8");
      if (!(keyObject instanceof String)) continue;
      String key = (String) keyObject;
      if (!key.endsWith(".boot.class.path")) continue;
      String value = System.getProperty(key);
      for (String entry : value.split(File.pathSeparator)) {
        Path path = Paths.get(entry);
        if (JAR_PATTERN.matches(path) && Files.isRegularFile(path)) {
          indexJarFile(path, jdk);
        }
      }
    }
  }

  @Override
  public Integer apply(Package pkg) {
    return writer.emitpackageinformationVertex(pkg);
  }
}
