package br.com.flexclock.jasper;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import br.com.flexclock.reports.ReportFamily;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.util.JRLoader;

/**
 * Loads the compiled JRXML templates of the nine report families.
 *
 * <p>The templates are compiled at build time by {@link JasperTemplateCompiler}, which the Maven build
 * runs right after the classes are compiled; the compiled {@code .jasper} files travel inside the
 * application jar. The catalogue loads them once and keeps them, because compiling a report is an
 * expensive step that no request should pay.
 *
 * <p>When a {@code .jasper} file is not on the classpath (a run from an IDE that skipped the build
 * step, or a single template test), the catalogue compiles the versioned JRXML resource on demand. The
 * JRXML is the source of truth: the compiled form is a build artifact and is never committed.
 *
 * <p>Decision 0052; architecture contract, sections 2 and 4.2.
 */
@Component
public class JasperReportCatalog {

	private static final Logger log = LoggerFactory.getLogger(JasperReportCatalog.class);

	/** Directory of the report templates inside the classpath. */
	public static final String TEMPLATE_DIRECTORY = "reports/";

	private final Map<ReportFamily, JasperReport> compiled = new ConcurrentHashMap<>();

	/**
	 * Returns the compiled report of a family, compiling it from the JRXML on first use when the build
	 * artifact is missing.
	 *
	 * @param family the report family
	 * @return the compiled report, reused for the following calls
	 */
	public JasperReport report(ReportFamily family) {
		return compiled.computeIfAbsent(family, this::load);
	}

	private JasperReport load(ReportFamily family) {
		String baseName = TEMPLATE_DIRECTORY + family.templateName();
		try (InputStream compiledStream = open(baseName + ".jasper")) {
			if (compiledStream != null) {
				return (JasperReport) JRLoader.loadObject(compiledStream);
			}
		}
		catch (Exception exception) {
			log.warn("Compiled template {} is not usable, compiling the JRXML instead", baseName, exception);
		}

		try (InputStream sourceStream = open(baseName + ".jrxml")) {
			if (sourceStream == null) {
				throw new IllegalStateException("Report template not found on the classpath: " + baseName);
			}
			return JasperCompileManager.compileReport(sourceStream);
		}
		catch (Exception exception) {
			throw new IllegalStateException("Could not compile the report template " + baseName, exception);
		}
	}

	private InputStream open(String resource) {
		ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
		InputStream stream = contextLoader == null ? null : contextLoader.getResourceAsStream(resource);
		return stream != null ? stream : JasperReportCatalog.class.getClassLoader().getResourceAsStream(resource);
	}
}
