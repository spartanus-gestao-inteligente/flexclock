package br.com.flexclock.jasper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import br.com.flexclock.reports.ReportFamily;
import net.sf.jasperreports.engine.JRField;
import net.sf.jasperreports.engine.JRParameter;
import net.sf.jasperreports.engine.JasperCompileManager;
import net.sf.jasperreports.engine.JasperReport;

/**
 * Proves that every versioned JRXML template compiles with JasperReports and that it declares the
 * parameters and the fields the fill path provides.
 *
 * <p>This test is the guard against drift between the template and the Java side: a renamed field or a
 * missing parameter fails here instead of failing at the first PDF request. It also proves the build
 * step: the compiled {@code .jasper} of each template is present next to the JRXML after the Maven
 * build, so the runtime loads a compiled report and never compiles one per request.
 *
 * <p>Decision 0052; architecture contract, sections 2 and 4.2.
 */
class JasperTemplateCompilationTest {

	private static final Set<String> SHARED_PARAMETERS = new LinkedHashSet<>(ReportPdfService.requiredParameters());

	private static final Set<String> BREAKDOWN_PARAMETERS = Set.of("breakdownTitle", "breakdown1", "breakdown2",
			"breakdown3", "breakdown4", "breakdown5", "breakdown6");

	@ParameterizedTest
	@EnumSource(ReportFamily.class)
	void everyTemplateCompilesAndDeclaresTheContractedParameters(ReportFamily family) throws Exception {
		JasperReport report = compile(family);

		Set<String> declared = new LinkedHashSet<>(
				Arrays.stream(report.getParameters()).map(JRParameter::getName).filter(name -> !name.startsWith("JRS"))
						.toList());
		assertThat(declared).containsAll(SHARED_PARAMETERS);
		if (withBreakdown(family)) {
			assertThat(declared).containsAll(BREAKDOWN_PARAMETERS);
		}
		assertThat(report.getName()).startsWith("FlexClock");
	}

	@ParameterizedTest
	@EnumSource(ReportFamily.class)
	void everyTemplateDeclaresTheFieldsOfItsTable(ReportFamily family) throws Exception {
		JasperReport report = compile(family);

		List<String> declared = Arrays.stream(report.getFields()).map(JRField::getName).toList();
		assertThat(declared).containsExactlyElementsOf(expectedFields(family));
	}

	@ParameterizedTest
	@EnumSource(ReportFamily.class)
	void theBuildStepLeftTheCompiledTemplateNextToTheSource(ReportFamily family) {
		String resource = JasperReportCatalog.TEMPLATE_DIRECTORY + family.templateName() + ".jasper";

		try (InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
			assertThat(stream).as("compiled template %s must exist after the build", resource).isNotNull();
		}
		catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	@Test
	void theTemplateDirectoryHoldsExactlyTheNineFamilies() {
		assertThat(ReportFamily.values()).hasSize(9);
		assertThat(Arrays.stream(ReportFamily.values()).map(ReportFamily::templateName).toList()).containsExactly(
				"time_report", "failures_report", "hallucination_report", "quality_report", "graph_usage_report",
				"token_economy_report", "sources_report", "downloads_report", "uploads_report");
	}

	private JasperReport compile(ReportFamily family) throws Exception {
		String resource = JasperReportCatalog.TEMPLATE_DIRECTORY + family.templateName() + ".jrxml";
		try (InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
			assertThat(stream).as("template %s must be on the classpath", resource).isNotNull();
			return JasperCompileManager.compileReport(stream);
		}
	}

	private boolean withBreakdown(ReportFamily family) {
		return family == ReportFamily.FAILURES || family == ReportFamily.QUALITY || family == ReportFamily.SOURCES;
	}

	private List<String> expectedFields(ReportFamily family) {
		return switch (family) {
			case TIME -> List.of("startText", "endText", "durationText", "pausedText", "activeText", "procedureTitle",
					"sessionId");
			case FAILURES -> List.of("occurredText", "typeLabel", "severityLabel", "agentId", "codeText",
					"messageText");
			case HALLUCINATION -> List.of("occurredText", "agentId", "sessionId", "responseRef");
			case QUALITY -> List.of("occurredText", "sessionId", "scoreText", "adherenceText", "evidenceText",
					"processText", "artifactText", "honestyText", "authority");
			case GRAPH_USAGE -> List.of("occurredText", "agentId", "sessionId", "queryText", "nodeText",
					"beforeWorkText");
			case TOKEN_ECONOMY -> List.of("occurredText", "modelId", "inputText", "outputText", "baselineText",
					"savingsText", "stateText");
			case SOURCES -> List.of("occurredText", "agentId", "origin", "source", "purpose", "result", "citedText");
			case DOWNLOADS -> List.of("occurredText", "agentId", "sourceOrDestination", "item", "itemType", "sizeText",
					"result");
			case UPLOADS -> List.of("occurredText", "agentId", "sourceOrDestination", "item", "itemType", "sizeText",
					"result", "sentText");
		};
	}
}
