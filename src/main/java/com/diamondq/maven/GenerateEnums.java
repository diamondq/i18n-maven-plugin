package com.diamondq.maven;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.util.Scanner;
import org.sonatype.plexus.build.incremental.BuildContext;

/**
 * Goal which generates an Enum from the string keys in a Properties inputFile
 */
@Mojo(name = "generateEnums", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class GenerateEnums extends AbstractMojo {
	@Component
	protected BuildContext	buildContext;

	/**
	 * Location of the inputFile.
	 */
	@Parameter(defaultValue = "${project.build.directory}/generated-sources/i18n", property = "outputDir",
		required = true)
	private File			outputDirectory;

	@Parameter(property = "propsDir", required = true)
	private File			propsDir;

	/**
	 * The suffix of the properties inputFile
	 */
	@Parameter(defaultValue = ".properties", required = true)
	private String			propsSuffix;

	/**
	 * Whether to recursive down the props directory.
	 */
	@Parameter(defaultValue = "true", required = true)
	private boolean			recurse;

	@Parameter(defaultValue = "_", required = true)
	private String			packageSeparator;

	@Parameter(required = false)
	private String[]		keyPrefixes;

	@Parameter(required = false)
	private String			implementsClass;

	private StringBuilder	debugBuilder;

	private static class IntakeFile {
		/**
		 * The file that contains the properties to read
		 */
		public final File		inputFile;

		/**
		 * The file that will contain the enumeration
		 */
		public final File		outputFile;

		/**
		 * The name of the output file (no path, and no suffix)
		 */
		public final String		outputName;

		/**
		 * The name of the input file (no path, and no suffix)
		 */
		public final String		inputName;

		public final String[]	packageName;

		public final String		javaPackageName;

		public final Path		relPath;

		public IntakeFile(File pInputFile, String pInputName, File pOutputFile, String pOutputName,
			String[] pPackageName, String pJavaPackageName, Path pRelPath) {
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
			StringBuilder sb = new StringBuilder();
			sb.append("IntakeFile[inputFile=").append(inputFile).append(", packageName=[")
				.append(String.join(", ", packageName));
			sb.append("], javaPackageName=").append(javaPackageName).append(", relPath=").append(relPath).append("]");
			return sb.toString();
		}
	}

	@SuppressWarnings("unused")
	private void recurseDirectory(File pDir, File pRootDir, File pOutputDir, List<IntakeFile> pResults) {
		for (File file : pDir.listFiles()) {
			if (file.isDirectory() == true)
				recurseDirectory(file, pRootDir, pOutputDir, pResults);
			else if (file.isFile() == true) {
				if (file.getName().endsWith(propsSuffix)) {
					buildIntakeFile(pRootDir, pOutputDir, pResults, file);
				}
			}
		}
	}

	private void buildIntakeFile(File pInputDir, File pOutputDir, List<IntakeFile> pResults, File pFile) {

		/* Generate the relative path between the file and the root of the input directory */

		Path relPath = pInputDir.toPath().relativize(pFile.getParentFile().toPath());

		/* Generate the output file */

		String outName = pFile.getName();
		int offset = outName.lastIndexOf('.');
		if (offset != -1)
			outName = outName.substring(0, offset);
		String inName = outName;
		if (Character.isUpperCase(outName.charAt(0)) == false)
			outName = Character.toUpperCase(outName.charAt(0)) + outName.substring(1);
		File outputFile = pOutputDir.toPath().resolve(relPath).resolve(outName + ".java").toFile();

		/* Check to see if the output file is 'up-to-date' */

		if (buildContext.isUptodate(outputFile, pFile) == true)
			return;

		/* Build the information needed for processing */

		String packageBuilder = "";
		List<String> packageList = new ArrayList<>();
		for (int i = relPath.getNameCount() - 1; i >= 0; i--) {
			packageBuilder = relPath.getName(i) + packageSeparator + packageBuilder;
			packageList.add(0, packageBuilder.toUpperCase(Locale.ENGLISH));
		}
		StringBuilder javaPackageBuilder = new StringBuilder();
		for (int i = 0; i < relPath.getNameCount(); i++) {
			if (i > 0)
				javaPackageBuilder.append('.');
			javaPackageBuilder.append(relPath.getName(i));
		}

		pResults.add(new IntakeFile(pFile, inName, outputFile, outName, packageList.toArray(new String[0]),
			javaPackageBuilder.toString(), relPath));
	}

	private List<IntakeFile> processPropsDir() throws MojoExecutionException {
		File inDir = propsDir;
		if ((inDir == null) || (inDir.exists() == false))
			throw new MojoExecutionException(
				"The propsDir " + (inDir == null ? "" : inDir.toString()) + " doesn't exist");
		File outDir = outputDirectory;
		inDir = inDir.getAbsoluteFile();
		List<IntakeFile> results = new ArrayList<>();
		Scanner scanner = buildContext.newScanner(inDir, true);
		scanner.setIncludes(new String[] {"**/*" + propsSuffix});
		scanner.scan();
		String[] includedFiles = scanner.getIncludedFiles();
		if (includedFiles != null) {
			for (String includedFile : includedFiles) {
				File file = new File(scanner.getBasedir(), includedFile);
				buildIntakeFile(inDir, outDir, results, file);
			}
		}

		// recurseDirectory(inDir, inDir, results);
		return results;
	}

	@Override
	public void execute() throws MojoExecutionException {

		debugBuilder = new StringBuilder();

		if (buildContext == null)
			throw new MojoExecutionException("BuildContext was not set.");

		handleDefaults();

		File outDir = outputDirectory;

		if (!outDir.exists())
			outDir.mkdirs();

		boolean failed = false;

		Scanner deleteScanner = buildContext.newDeleteScanner(propsDir);
		deleteScanner.setIncludes(new String[] {"**/*" + propsSuffix});
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
				if (offset != -1)
					outName = outName.substring(0, offset);
				if (Character.isUpperCase(outName.charAt(0)) == false)
					outName = Character.toUpperCase(outName.charAt(0)) + outName.substring(1);
				File outputFile = outputDirectory.toPath().resolve(relPath).resolve(outName + ".java").toFile();
				if (outputFile.exists() == true) {
					outputFile.delete();
					buildContext.refresh(outputFile);
				}
			}
		}

		/* For each inputFile to process */

		for (IntakeFile intakeFile : processPropsDir()) {

			getLog().info("Processing: " + intakeFile.inputFile.getAbsolutePath());
			// debugBuilder.append("Processing: " + intakeFile.file + "\n");
			// debugBuilder.append("BuildContext3: " + (buildContext == null ? "(NULL)" : buildContext.toString()) +
			// "\n");

			/* Read the properties inputFile */

			Properties p = new Properties();
			try {
				p.load(new FileReader(intakeFile.inputFile));
			}
			catch (IOException ex) {
				buildContext.addMessage(intakeFile.inputFile.getAbsoluteFile(), 0, 0, "Unable to parse inputFile",
					BuildContext.SEVERITY_ERROR, ex);
				failed = true;
				continue;
			}

			/* Now generate an enum class */

			if (intakeFile.outputFile.getParentFile().exists() == false)
				intakeFile.outputFile.getParentFile().mkdirs();
			try {
				try (FileWriter fw = new FileWriter(intakeFile.outputFile)) {
					try (BufferedWriter bw = new BufferedWriter(fw)) {
						dumpDebug(bw);
						bw.append("package ");
						bw.append(intakeFile.javaPackageName);
						bw.append(";\n");
						bw.append("\n");
						if ((implementsClass != null) && (implementsClass.isEmpty() == false)) {
							bw.append("import ").append(implementsClass).append(";\n");
							bw.append("\n");
						}
						bw.append("import java.util.Locale;\n");
						bw.append("import java.util.ResourceBundle;\n");
						bw.append("\n");

						bw.append("public enum ");
						bw.append(intakeFile.outputName);
						if ((implementsClass != null) && (implementsClass.isEmpty() == false)) {
							int implementsOffset = implementsClass.lastIndexOf('.');
							String className;
							if (implementsOffset == -1)
								className = implementsClass;
							else
								className = implementsClass.substring(implementsOffset + 1);
							bw.append(" implements ").append(className);
						}
						bw.append(" {\n");
						bw.append("\n");
						boolean isFirst = true;
						List<String> keys = new ArrayList<>();
						for (Object keyObj : p.keySet()) {
							String key = keyObj.toString();
							keys.add(key);
						}
						Collections.sort(keys);

						for (String actualKey : keys) {
							String key = actualKey.toUpperCase(Locale.ENGLISH);

							/* See if the front of the key matches any of the packageNames */

							for (String prefix : intakeFile.packageName) {
								if (key.startsWith(prefix)) {

									/* Remove the key */

									key = key.substring(prefix.length());

								}
							}

							if (isFirst == true)
								isFirst = false;
							else
								bw.append(", //\n");
							bw.append("\t");
							bw.append(key);
							bw.append("(\"");
							bw.append(actualKey);
							bw.append("\")");
						}
						bw.append(";\n");
						bw.append("\n");

						bw.append("\tprivate final String mCode;\n");
						bw.append("\n");
						bw.append("\tprivate ").append(intakeFile.outputName).append("(String pCode) {\n");
						bw.append("\t\tmCode = pCode;\n");
						bw.append("\t}\n");
						bw.append("\n");
						if ((implementsClass != null) && (implementsClass.isEmpty() == false))
							bw.append("\t@Override\n");
						bw.append("\tpublic String getCode() {\n");
						bw.append("\t\treturn mCode;\n");
						bw.append("\t}\n");
						bw.append("\n");

						if ((implementsClass != null) && (implementsClass.isEmpty() == false))
							bw.append("\t@Override\n");
						bw.append("\tpublic ResourceBundle getBundle(Locale pLocale) {\n");
						bw.append("\t\treturn ResourceBundle.getBundle(\"").append(intakeFile.javaPackageName)
							.append(".").append(intakeFile.inputName).append("\", pLocale);\n");
						bw.append("\t}\n");
						bw.append("\n");
						bw.append("}\n");
					}
				}
				buildContext.refresh(intakeFile.outputFile);
			}
			catch (IOException ex) {
				throw new MojoExecutionException(
					"Unable to write to enum inputFile: " + intakeFile.outputFile.getAbsolutePath(), ex);
			}

		}
		if (failed == true)
			throw new MojoExecutionException("Failed to build enumerations due to previous errors");
	}

	private void dumpDebug(BufferedWriter pWriter) throws IOException {
		if (debugBuilder.length() > 0) {
			pWriter.append("/*\n");
			pWriter.append(debugBuilder.toString());
			pWriter.append("*/\n");
		}
	}

	private void handleDefaults() {
		if (outputDirectory == null)
			outputDirectory = new File("target/generated-sources/i18n");
		if (propsSuffix == null)
			propsSuffix = ".properties";
		if (packageSeparator == null)
			packageSeparator = "_";
	}
}
