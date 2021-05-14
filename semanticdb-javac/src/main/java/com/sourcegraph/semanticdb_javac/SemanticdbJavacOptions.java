package com.sourcegraph.semanticdb_javac;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import javax.tools.FileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;

import com.sun.tools.javac.util.Context;

import static javax.tools.StandardLocation.CLASS_OUTPUT;

/**
 * Settings that can be configured alongside the -Xplugin compiler option.
 */
public class SemanticdbJavacOptions {

  /**
   * The directory to place META-INF and its .semanticdb files
   */
  public Path targetroot;

  public Path sourceroot;
  public boolean includeText = false;
  public boolean verboseEnabled = false;
  public final ArrayList<String> errors;

  public static String stubClassName = "META-INF-stub";

  public SemanticdbJavacOptions() {
    errors = new ArrayList<>();
  }

  public static String missingRequiredDirectoryOption(String option) {
    return String.format(
        "missing argument '-%s'. To fix this problem, update the Java compiler option "
            + "'-Xplugin:semanticdb -%s:DIRECTORY' where DIRECTORY is the path to a valid directory.",
        option, option);
  }

  public static String JAVAC_CLASSES_DIR_ARG = "javac-classes-directory";

  public static SemanticdbJavacOptions parse(String[] args, Context ctx) {
    SemanticdbJavacOptions result = new SemanticdbJavacOptions();
    boolean useJavacClassesDir = false;
    for (String arg : args) {
      if (arg.startsWith("-targetroot:")) {
        String argValue = arg.substring("-targetroot:".length());
        if (argValue.equals(JAVAC_CLASSES_DIR_ARG)) {
          useJavacClassesDir = true;
          result.targetroot = getJavacClassesDir(result, ctx);
        } else {
          result.targetroot = Paths.get(argValue);
        }
      } else if (arg.startsWith("-sourceroot:")) {
        result.sourceroot = Paths.get(arg.substring("-sourceroot:".length())).normalize();
      } else if (arg.equals("-text:on")) {
        result.includeText = true;
      } else if (arg.equals("-text:off")) {
        result.includeText = false;
      } else if (arg.equals("-verbose")) {
        result.verboseEnabled = true;
      } else if (arg.equals("-verbose:on")) {
        result.verboseEnabled = true;
      } else if (arg.equals("-verbose:off")) {
        result.verboseEnabled = false;
      } else {
        result.errors.add(String.format("unknown flag '%s'\n", arg));
      }
    }
    if (result.targetroot == null && !useJavacClassesDir) {
      result.errors.add(missingRequiredDirectoryOption("targetroot"));
    }
    if (result.sourceroot == null) {
      result.errors.add(missingRequiredDirectoryOption("sourceroot"));
    }
    return result;
  }

  private static Path getJavacClassesDir(SemanticdbJavacOptions result, Context ctx) {
    // I'm not aware of a better way to get the class output directory from javac
    Path outputDir = null;
    try {
      JavaFileManager fm = ctx.get(JavaFileManager.class);
      FileObject outputDirStub =
          fm.getJavaFileForOutput(CLASS_OUTPUT, stubClassName, JavaFileObject.Kind.CLASS, null);
      outputDir = Paths.get(outputDirStub.toUri()).toAbsolutePath().getParent();
    } catch (Exception e) {
      String errorMsg =
          String.format(
              "-targetroot:%s passed but could not get the class output directory: %s",
              JAVAC_CLASSES_DIR_ARG,
              e.getMessage()
          );
      result.errors.add(errorMsg);
    }
    return outputDir;
  }
}
