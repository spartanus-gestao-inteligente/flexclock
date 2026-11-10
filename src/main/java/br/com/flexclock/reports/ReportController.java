package br.com.flexclock.reports;

import java.time.Instant;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import br.com.flexclock.common.ContractVersion;
import br.com.flexclock.common.ErrorResponse;
import br.com.flexclock.common.IdempotencyFilter;
import br.com.flexclock.dto.DashboardResponse;
import br.com.flexclock.dto.ReportRecordRequest;
import br.com.flexclock.dto.ReportRecordResponse;
import br.com.flexclock.dto.ReportResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

/**
 * Report surface of the backend: ingestion, aggregation, the consolidated dashboard and the PDF export.
 *
 * <p>Controllers stay thin: they bind, delegate and return a record or a file. Every rule lives in
 * {@link ReportService}. The read paths never mutate a report record, and the write path offers creation
 * only, guarded by the mandatory Idempotency-Key.
 *
 * <p>Architecture contract, section 6.1; decisions 0019, 0030, 0049, 0052.
 */
@RestController
@RequestMapping(ContractVersion.API_BASE_PATH + "/reports")
public class ReportController {

	private final ReportService reportService;

	public ReportController(ReportService reportService) {
		this.reportService = reportService;
	}

	/**
	 * Appends one record of a report family.
	 *
	 * @param family the family token of the path
	 * @param request the record to append
	 * @param idempotencyKey the key presented in the Idempotency-Key header
	 * @return the appended record
	 */
	@ApiResponse(responseCode = "201", description = "The record was appended.")
	@ApiResponse(responseCode = "400", description = "The record is not valid.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@ApiResponse(responseCode = "409", description = "The Idempotency-Key was reused with another payload.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@PostMapping("/{family}")
	public ResponseEntity<ReportRecordResponse> append(@PathVariable String family,
			@RequestBody ReportRecordRequest request,
			@RequestAttribute(name = IdempotencyFilter.IDEMPOTENCY_KEY_ATTRIBUTE, required = false) String idempotencyKey) {
		ReportRecordResponse response = reportService.append(family, request, idempotencyKey);
		return ResponseEntity.status(HttpStatus.CREATED).body(response);
	}

	/**
	 * Aggregates one family for one period.
	 *
	 * @param family the family token of the path
	 * @param period the period token, day when absent
	 * @param start the ISO date of the start of a custom period
	 * @param end the ISO date of the exclusive end of a custom period
	 * @param timezone the timezone, the configured default when absent
	 * @param locale the locale, the configured default when absent
	 * @param procedureId the procedure of a session period
	 * @return the aggregated report
	 */
	@ApiResponse(responseCode = "200", description = "The report of the period.")
	@ApiResponse(responseCode = "400", description = "The period, the timezone or the family is not valid.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@GetMapping("/{family}")
	public ReportResponse report(@PathVariable String family,
			@RequestParam(required = false) String period,
			@RequestParam(required = false, name = "start") String start,
			@RequestParam(required = false, name = "end") String end,
			@RequestParam(required = false) String timezone,
			@RequestParam(required = false) String locale,
			@RequestParam(required = false) Long procedureId) {
		return reportService.report(family, period, start, end, timezone, locale, procedureId);
	}

	/**
	 * Exports the PDF of one family for one period.
	 *
	 * @param family the family token of the path
	 * @param period the period token, day when absent
	 * @param start the ISO date of the start of a custom period
	 * @param end the ISO date of the exclusive end of a custom period
	 * @param timezone the timezone, the configured default when absent
	 * @param locale the locale, the configured default when absent
	 * @param procedureId the procedure of a session period
	 * @return the PDF file
	 */
	@ApiResponse(responseCode = "200", description = "The PDF of the report.",
			content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE))
	@ApiResponse(responseCode = "400", description = "The period, the timezone or the family is not valid.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@GetMapping(value = "/{family}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
	public ResponseEntity<byte[]> pdf(@PathVariable String family,
			@RequestParam(required = false) String period,
			@RequestParam(required = false, name = "start") String start,
			@RequestParam(required = false, name = "end") String end,
			@RequestParam(required = false) String timezone,
			@RequestParam(required = false) String locale,
			@RequestParam(required = false) Long procedureId) {
		ReportService.PdfExport export = reportService.pdf(family, period, start, end, timezone, locale,
				procedureId);
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_PDF);
		headers.setContentDisposition(ContentDisposition.attachment().filename(export.fileName()).build());
		headers.set("X-Generated-At", Instant.now().toString());
		return ResponseEntity.ok().headers(headers).body(export.content());
	}

	/**
	 * Consolidated dashboard of the nine families for one period, consumed by the Monitor window.
	 *
	 * @param period the period token, day when absent
	 * @param start the ISO date of the start of a custom period
	 * @param end the ISO date of the exclusive end of a custom period
	 * @param timezone the timezone, the configured default when absent
	 * @param locale the locale, the configured default when absent
	 * @param procedureId the procedure of a session period
	 * @return the consolidated dashboard
	 */
	@ApiResponse(responseCode = "200", description = "The nine family reports of the period.")
	@ApiResponse(responseCode = "400", description = "The period or the timezone is not valid.",
			content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
					schema = @Schema(implementation = ErrorResponse.class)))
	@GetMapping("/dashboard")
	public DashboardResponse dashboard(@RequestParam(required = false) String period,
			@RequestParam(required = false, name = "start") String start,
			@RequestParam(required = false, name = "end") String end,
			@RequestParam(required = false) String timezone,
			@RequestParam(required = false) String locale,
			@RequestParam(required = false) Long procedureId) {
		return reportService.dashboard(period, start, end, timezone, locale, procedureId);
	}
}
