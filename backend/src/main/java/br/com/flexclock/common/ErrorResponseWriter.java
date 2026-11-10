package br.com.flexclock.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes the single error envelope straight to the servlet response.
 *
 * <p>Servlet filters run before the dispatcher, so they cannot use the controller advice. This
 * component keeps the filter rejections in exactly the same shape as every other error of the API.
 */
@Component
public class ErrorResponseWriter {

	private final ObjectMapper objectMapper;

	public ErrorResponseWriter(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/**
	 * Writes the error envelope and sets the HTTP status of the code.
	 *
	 * @param response the servlet response
	 * @param code the stable error code
	 * @param field the field or resource involved, may be null
	 * @param detail an optional English detail, may be null
	 * @throws IOException when the response cannot be written
	 */
	public void write(HttpServletResponse response, ErrorCode code, String field, String detail)
			throws IOException {
		response.setStatus(code.status().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		objectMapper.writeValue(response.getOutputStream(), ErrorResponse.of(code, field, detail));
	}
}
