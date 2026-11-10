package br.com.flexclock.jasper;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import br.com.flexclock.dto.ReportResponse;
import br.com.flexclock.jasper.ReportFillDataFactory.ReportFillData;
import br.com.flexclock.reports.ReportPeriodWindow;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.data.JRMapCollectionDataSource;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;

/**
 * Fills the compiled template of a report family with the aggregated data of one period and exports the
 * PDF.
 *
 * <p>The backend is the only PDF generator of the product: the Flutter layer receives the file and saves
 * it, it never generates one. Nothing is written to disk here, so no generated PDF can reach the
 * repository.
 *
 * <p>Decisions 0030, 0031, 0052; requirements RF-074.
 */
@Service
public class ReportPdfService {

	private final JasperReportCatalog catalog;
	private final ReportFillDataFactory fillDataFactory;
	private final String logoPath;

	public ReportPdfService(JasperReportCatalog catalog, ReportFillDataFactory fillDataFactory,
			@Value("${flexclock.report.logo-path:}") String logoPath) {
		this.catalog = catalog;
		this.fillDataFactory = fillDataFactory;
		this.logoPath = logoPath;
	}

	/**
	 * Builds the PDF of one report.
	 *
	 * @param response the aggregated report of the family
	 * @param window the resolved window of the report
	 * @param generatedAt the instant the report is generated
	 * @return the PDF bytes
	 */
	public byte[] pdf(ReportResponse response, ReportPeriodWindow window, Instant generatedAt) {
		ReportFillData fillData = fillDataFactory.build(response, window, generatedAt, resolvedLogoPath());
		try {
			JasperPrint print = JasperFillManager.fillReport(catalog.report(response.family()),
					fillData.parameters(), new JRMapCollectionDataSource(fillData.rows()));
			ByteArrayOutputStream output = new ByteArrayOutputStream();
			JasperExportManager.exportReportToPdfStream(print, output);
			return output.toByteArray();
		}
		catch (JRException exception) {
			throw new IllegalStateException("Could not fill the " + response.family().wireName() + " report",
					exception);
		}
	}

	/**
	 * Resolves the product mark used by the template header.
	 *
	 * <p>The path is configuration, never a committed binary: when it is not configured or the file does
	 * not exist the template prints the report title alone.
	 *
	 * @return the absolute path of the mark, null when it is not available
	 */
	private String resolvedLogoPath() {
		if (logoPath == null || logoPath.isBlank()) {
			return null;
		}
		return java.nio.file.Files.isRegularFile(java.nio.file.Path.of(logoPath)) ? logoPath : null;
	}

	/**
	 * Suggests the file name of the exported report.
	 *
	 * @param response the aggregated report
	 * @param window the resolved window
	 * @return the file name of the PDF
	 */
	public String fileName(ReportResponse response, ReportPeriodWindow window) {
		ZoneId zone = window.zone();
		return "flex-clock-" + response.family().wireName() + "-"
				+ window.start().atZone(zone).toLocalDate() + "-" + window.end().atZone(zone).toLocalDate()
				+ ".pdf";
	}

	/**
	 * Reports whether a logo path is configured and readable, used by the diagnostics of the report
	 * surface.
	 *
	 * @return the configured and readable path, null otherwise
	 */
	public String effectiveLogoPath() {
		return resolvedLogoPath();
	}

	/**
	 * Reports the parameters that the templates require, so a test can prove that every template declares
	 * them.
	 *
	 * @return the required parameter names
	 */
	public static java.util.List<String> requiredParameters() {
		return java.util.List.of("logoPath", "reportName", "periodStart", "periodEnd", "generatedAt", "footerNote",
				"emptyMessage", "chartTitle", "chartNote", "chartDataSource", "kpi1Label", "kpi1Value", "kpi2Label",
				"kpi2Value", "kpi3Label", "kpi3Value");
	}
}
