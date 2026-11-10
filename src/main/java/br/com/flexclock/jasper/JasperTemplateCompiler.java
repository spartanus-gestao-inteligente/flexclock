package br.com.flexclock.jasper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import net.sf.jasperreports.engine.JasperCompileManager;

/**
 * Compiles every versioned JRXML template into the {@code .jasper} form the runtime loads.
 *
 * <p>The Maven build runs this class in the {@code process-classes} phase, right after the resources
 * and the classes are in {@code target/classes}, so a template that does not compile fails the build
 * and the compiled form ships inside the jar. The compiled form is a build artifact: it is generated in
 * {@code target} and never committed.
 *
 * <p>The class is also the entry point used by the build, which is why it has a {@code main} method:
 * {@code java -cp <runtime classpath> br.com.flexclock.jasper.JasperTemplateCompiler <directory>}.
 *
 * <p>Decision 0052; requirements RF-074, RNF about the committed artifact.
 */
public final class JasperTemplateCompiler {

	private JasperTemplateCompiler() {
		// Utility.
	}

	/**
	 * Compiles every JRXML of a directory.
	 *
	 * @param directory the directory that holds the JRXML templates
	 * @return the compiled files, in name order
	 * @throws IOException when the directory cannot be listed
	 * @throws IllegalStateException when a template does not compile
	 */
	public static List<Path> compileDirectory(Path directory) throws IOException {
		if (!Files.isDirectory(directory)) {
			throw new IllegalStateException("Template directory not found: " + directory);
		}

		List<Path> sources;
		try (Stream<Path> files = Files.list(directory)) {
			sources = files.filter(path -> path.getFileName().toString().endsWith(".jrxml"))
					.sorted(Comparator.comparing(path -> path.getFileName().toString()))
					.toList();
		}
		if (sources.isEmpty()) {
			throw new IllegalStateException("No JRXML template found in " + directory);
		}

		List<Path> compiledFiles = new ArrayList<>();
		for (Path source : sources) {
			Path target = replaceExtension(source, ".jasper");
			try {
				JasperCompileManager.compileReportToFile(source.toString(), target.toString());
			}
			catch (Exception exception) {
				throw new IllegalStateException("Report template does not compile: " + source, exception);
			}
			compiledFiles.add(target);
		}
		return compiledFiles;
	}

	/**
	 * Build entry point.
	 *
	 * @param arguments the directory that holds the JRXML templates, {@code target/classes/reports} by
	 * default
	 * @throws Exception when the directory cannot be read or a template does not compile
	 */
	public static void main(String[] arguments) throws Exception {
		Path directory = arguments.length > 0 ? Path.of(arguments[0])
				: Path.of("target", "classes", "reports");
		for (Path file : compileDirectory(directory.toAbsolutePath())) {
			System.out.println("[flex-clock] compiled " + file.getFileName() + " (" + Files.size(file) + " bytes)");
		}
	}

	private static Path replaceExtension(Path source, String extension) {
		String name = source.getFileName().toString();
		int dot = name.lastIndexOf('.');
		String base = dot < 0 ? name : name.substring(0, dot);
		return source.resolveSibling(base + extension);
	}
}
