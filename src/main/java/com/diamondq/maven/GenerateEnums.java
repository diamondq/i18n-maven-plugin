package com.diamondq.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.util.Scanner;
import org.sonatype.plexus.build.incremental.BuildContext;

import javax.inject.Inject;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Goal which generates an Enum from the string keys in a Properties inputFile
 */
@SuppressWarnings("HardcodedFileSeparator") /* Maven property paths and Plexus scanner patterns always use '/' */
@Mojo(name = "generateEnums", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class GenerateEnums extends AbstractMojo {
  @Inject protected BuildContext buildContext;

  /**
   * Location of the inputFile.
   */
  @Parameter(defaultValue = "${project.build.directory}/generated-sources/i18n",
             property = "outputDir",
             required = true) private File outputDirectory;

  @Parameter(property = "propsDir", required = true) private File propsDir;

  /**
   * The suffix of the properties inputFile
   */
  @Parameter(defaultValue = ".properties", required = true) private String propsSuffix;

  /**
   * Whether to recursive down the directory.
   */
  @Parameter(defaultValue = "true", required = true) private boolean recurse;

  @Parameter(defaultValue = "_", required = true) private String packageSeparator;

  @Parameter() private String[] keyPrefixes;

  @Parameter() private String implementsClass;

  private static class IntakeFile {
    /**
     * The file that contains the properties to read
     */
    public final File inputFile;

    /**
     * The file that will contain the enumeration
     */
    public final File outputFile;

    /**
     * The name of the output file (no path, and no suffix)
     */
    public final String outputName;

    /**
     * The name of the input file (no path, and no suffix)
     */
    public final String inputName;

    public final String[] packageName;

    public final String javaPackageName;

    public final Path relPath;

    public IntakeFile(File pInputFile, String pInputName, File pOutputFile, String pOutputName, String[] pPackageName,
                      String pJavaPackageName, Path pRelPath) {
      super();
      inputFile = pInputFile;
      inputName = pInputName;
      outputFile = pOutputFile;
      outputName = pOutputName;
      packageName = pPackageName;
      javaPackageName = pJavaPackageName;
      relPath = pRelPath;
    }

    @Override
    public String toString() {
      return "IntakeFile[inputFile="
             + inputFile
             + ", packageName=["
             + String.join(", ", packageName)
             + "], javaPackageName="
             + javaPackageName
             + ", relPath="
             + relPath
             + "]";
    }
  }

  private void buildIntakeFile(File pInputDir, File pOutputDir, List<IntakeFile> pResults, File pFile) {

    /* Generate the relative path between the file and the root of the input directory */

    Path relPath = pInputDir.toPath().relativize(pFile.getParentFile().toPath());

    /* Generate the output file */

    String outName = pFile.getName();
    int offset = outName.lastIndexOf('.');
    if (offset != -1) outName = outName.substring(0, offset);
    String inName = outName;
    if (!Character.isUpperCase(outName.charAt(0)))
      outName = Character.toUpperCase(outName.charAt(0)) + outName.substring(1);
    File outputFile = pOutputDir.toPath().resolve(relPath).resolve(outName + ".java").toFile();

    /* Check to see if the output file is 'up to date' */

    if (buildContext.isUptodate(outputFile, pFile)) return;

    /* Build the information needed for processing */

    StringBuilder packageBuilder = new StringBuilder();
    List<String> packageList = new ArrayList<>();
    for (int i = relPath.getNameCount() - 1; i >= 0; i--) {
      packageBuilder.insert(0, relPath.getName(i) + packageSeparator);
      packageList.add(0, packageBuilder.toString().toUpperCase(Locale.ENGLISH));
    }
    StringBuilder javaPackageBuilder = new StringBuilder();
    for (int i = 0; i < relPath.getNameCount(); i++) {
      if (i > 0) javaPackageBuilder.append('.');
      javaPackageBuilder.append(relPath.getName(i));
    }

    pResults.add(new IntakeFile(pFile,
      inName,
      outputFile,
      outName,
      packageList.toArray(new String[0]),
      javaPackageBuilder.toString(),
      relPath
    ));
  }

  private List<IntakeFile> processPropsDir() throws MojoExecutionException {
    File inDir = propsDir;
    if ((inDir == null) || (!inDir.exists()))
      throw new MojoExecutionException("The propsDir " + (inDir == null ? "" : inDir.toString()) + " doesn't exist");
    File outDir = outputDirectory;
    inDir = inDir.getAbsoluteFile();
    List<IntakeFile> results = new ArrayList<>();
    Scanner scanner = buildContext.newScanner(inDir, true);
    scanner.setIncludes(new String[] { "**/*" + propsSuffix });
    scanner.scan();
    String[] includedFiles = scanner.getIncludedFiles();
    if (includedFiles != null) {
      for (String includedFile : includedFiles) {
        File file = new File(scanner.getBasedir(), includedFile);
        buildIntakeFile(inDir, outDir, results, file);
      }
    }

    return results;
  }

  @Override
  public void execute() throws MojoExecutionException {

    if (buildContext == null) throw new MojoExecutionException("BuildContext was not set.");

    handleDefaults();

    File outDir = outputDirectory;

    if (!outDir.exists())
      if (!outDir.mkdirs()) throw new IllegalStateException("Unable to create output directory " + outDir);

    boolean failed = false;

    Scanner deleteScanner = buildContext.newDeleteScanner(propsDir);
    deleteScanner.setIncludes(new String[] { "**/*" + propsSuffix });
    deleteScanner.scan();
    String[] deletedFiles = deleteScanner.getIncludedFiles();
    if (deletedFiles != null) {
      for (String deletedFile : deletedFiles) {
        File file = new File(deleteScanner.getBasedir(), deletedFile);

        /* Generate the relative path between the file and the root of the input directory */

        Path relPath = propsDir.toPath().relativize(file.getParentFile().toPath());

        /* Generate the output file */

        String outName = file.getName();
        int offset = outName.lastIndexOf('.');
        if (offset != -1) outName = outName.substring(0, offset);
        if (!Character.isUpperCase(outName.charAt(0)))
          outName = Character.toUpperCase(outName.charAt(0)) + outName.substring(1);
        File outputFile = outputDirectory.toPath().resolve(relPath).resolve(outName + ".java").toFile();
        if (outputFile.exists()) {
          if (!outputFile.delete()) throw new IllegalStateException("Unable to delete " + outputFile);
          buildContext.refresh(outputFile);
        }
      }
    }

    /* For each inputFile to process */

    for (IntakeFile intakeFile : processPropsDir()) {

      getLog().info("Processing: " + intakeFile.inputFile.getAbsolutePath());

      /* Read the properties inputFile */

      Properties p = new Properties();
      try (Reader reader = new InputStreamReader(new FileInputStream(intakeFile.inputFile), StandardCharsets.UTF_8)) {
        p.load(reader);
      }
      catch (IOException ex) {
        buildContext.addMessage(intakeFile.inputFile.getAbsoluteFile(),
          0,
          0,
          "Unable to parse inputFile",
          BuildContext.SEVERITY_ERROR,
          ex
        );
        failed = true;
        continue;
      }

      /* Now generate an enum class */

      if (!intakeFile.outputFile.getParentFile().exists()) {
        if (!intakeFile.outputFile.getParentFile().mkdirs())
          throw new IllegalStateException("Unable to create " + intakeFile.outputFile.getParentFile());
      }
      try {
        try (Writer fw = new OutputStreamWriter(new FileOutputStream(intakeFile.outputFile), StandardCharsets.UTF_8)) {
          try (BufferedWriter bw = new BufferedWriter(fw)) {
            bw.append("package ");
            bw.append(intakeFile.javaPackageName);
            bw.append(";\n");
            List<String> importList = new ArrayList<>();
            importList.add("com.diamondq.common.i18n.I18N");
            importList.add("java.util.Locale");
            importList.add("java.util.ResourceBundle");
            if ((implementsClass != null) && (!implementsClass.isEmpty())) importList.add(implementsClass);
            Map<String, String> importToShort = handleImports(importList, bw);
            bw.append("\n");

            bw.append("/**\n");
            bw.append(" * Message codes generated from the ").append(intakeFile.inputName).append(" resource bundle.\n");
            bw.append(" */\n");
            bw.append("public enum ");
            bw.append(intakeFile.outputName);
            if ((implementsClass != null) && (!implementsClass.isEmpty())) {
              String className = Objects.requireNonNull(importToShort.get(implementsClass));
              bw.append(" implements ").append(className);
            }
            bw.append(" {\n");
            bw.append("\n");
            List<String> keys = new ArrayList<>();
            for (Object keyObj : p.keySet()) {
              String key = keyObj.toString();
              keys.add(key);
            }
            Collections.sort(keys);

            for (int keyIndex = 0; keyIndex < keys.size(); keyIndex++) {
              String actualKey = keys.get(keyIndex);
              String key = actualKey.toUpperCase(Locale.ENGLISH);

              /* See if the front of the key matches any of the packageNames */

              for (String prefix : intakeFile.packageName) {
                if (key.startsWith(prefix)) {

                  /* Remove the key */

                  key = key.substring(prefix.length());

                }
              }

              bw.append("\t/**\n");
              bw.append("\t * The message with the code {@code ").append(actualKey).append("}\n");
              bw.append("\t */\n");
              bw.append("\t");
              bw.append(key);
              bw.append("(\"");
              bw.append(actualKey);
              bw.append("\")");
              bw.append(keyIndex < keys.size() - 1 ? ",\n" : ";\n");
            }
            if (keys.isEmpty()) bw.append("\t;\n");
            bw.append("\n");

            bw.append("\tprivate final String mCode;\n");
            bw.append("\n");
            bw.append("\t").append(intakeFile.outputName).append("(String pCode) {\n");
            bw.append("\t\tmCode = pCode;\n");
            bw.append("\t}\n");
            bw.append("\n");
            if ((implementsClass != null) && (!implementsClass.isEmpty())) bw.append("\t@Override\n");
            bw.append("\tpublic String getCode() {\n");
            bw.append("\t\treturn mCode;\n");
            bw.append("\t}\n");
            bw.append("\n");

            if ((implementsClass != null) && (!implementsClass.isEmpty())) bw.append("\t@Override\n");
            bw.append("\tpublic ResourceBundle getBundle(Locale pLocale) {\n");
            bw.append("\t\treturn I18N.getBundle(")
              .append(intakeFile.outputName)
              .append(".class, \"")
              .append(intakeFile.javaPackageName)
              .append(".")
              .append(intakeFile.inputName)
              .append("\", pLocale);\n");
            bw.append("\t}\n");
            bw.append("\n");
            bw.append("}\n");
          }
        }
        buildContext.refresh(intakeFile.outputFile);
      }
      catch (IOException ex) {
        throw new MojoExecutionException("Unable to write to enum inputFile: "
                                         + intakeFile.outputFile.getAbsolutePath(), ex
        );
      }

    }
    if (failed) throw new MojoExecutionException("Failed to build enumerations due to previous errors");
  }

  private Map<String, String> handleImports(Collection<String> pImports, BufferedWriter pWriter) throws IOException {
    List<String> imports = new ArrayList<>(pImports);
    Map<String, String> importToShort = new HashMap<>();
    Collections.sort(imports);
    String lastSection = ";;;;";
    for (String importStr : imports) {

      /* If the import string starts a new section, add an extra newline */

      int firstIndex = importStr.indexOf('.');
      String section = (firstIndex == -1 ? "" : importStr.substring(0, firstIndex));
      if (!section.equals(lastSection)) {
        lastSection = section;
        pWriter.append("\n");
      }

      /* Write the import */

      pWriter.append("import ").append(importStr).append(";\n");

      /* Set up the mapping table */

      int lastIndex = importStr.lastIndexOf('.');
      if (lastIndex == -1) importToShort.put(importStr, importStr);
      else importToShort.put(importStr, importStr.substring(lastIndex + 1));
    }
    return importToShort;
  }

  private void handleDefaults() {
    if (outputDirectory == null) outputDirectory = new File("target/generated-sources/i18n");
    if (propsSuffix == null) propsSuffix = ".properties";
    if (packageSeparator == null) packageSeparator = "_";
  }
}
