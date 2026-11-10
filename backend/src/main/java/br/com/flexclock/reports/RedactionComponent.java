package br.com.flexclock.reports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Removes credentials and secret values from free text and from URLs before a record is persisted,
 * redacted or logged.
 *
 * <p>The component keeps only the redacted shape: the original value is never returned, never stored
 * and never written to a log line. It reports which rules fired, so a record can carry the
 * {@code redacted} flag and an operator can see that something was removed without seeing what.
 *
 * <p>Decision 0033; requirements RF-052, RF-093; architecture contract, sections 3.2 and 8.2.
 */
@Component
public class RedactionComponent {

	/** Replacement written in place of a removed secret. */
	public static final String REDACTED = "[REDACTED]";

	private static final List<Rule> RULES = List.of(
			new Rule("private_key", Pattern.compile(
					"-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----")),
			new Rule("authorization_header", Pattern.compile(
					"(?i)\\b(bearer|basic|token)\\s+[A-Za-z0-9._~+/=-]{8,}")),
			new Rule("key_value", Pattern.compile(
					"(?i)\\b(password|passwd|pwd|secret|token|api[_-]?key|apikey|access[_-]?token"
							+ "|refresh[_-]?token|auth|authorization|credential|client[_-]?secret"
							+ "|passphrase|signature)\\b\\s*[:=]\\s*[^\\s&;,\"']+")),
			new Rule("query_parameter", Pattern.compile(
					"(?i)([?&](?:password|passwd|secret|token|api[_-]?key|apikey|access[_-]?token"
							+ "|refresh[_-]?token|sig|signature|auth|credential|key)=)[^&\\s#]+")),
			new Rule("url_userinfo", Pattern.compile("://[^/\\s:@]+:[^/\\s@]+@")),
			new Rule("aws_access_key", Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b")),
			new Rule("github_token", Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b")),
			new Rule("slack_token", Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b")),
			new Rule("jwt", Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b")));

	/** Upper bound of passes, so a replacement that creates a new match can never loop forever. */
	private static final int MAX_PASSES = 4;

	/**
	 * Redacts one text value.
	 *
	 * @param value the original text, may be null
	 * @return the redaction result, never null; the value is null only when the input was null
	 */
	public RedactionResult redact(String value) {
		if (value == null) {
			return new RedactionResult(null, false, List.of());
		}

		String current = value;
		List<String> fired = new ArrayList<>();
		for (int pass = 0; pass < MAX_PASSES; pass++) {
			boolean changed = false;
			for (Rule rule : RULES) {
				Matcher matcher = rule.pattern().matcher(current);
				StringBuffer buffer = new StringBuffer();
				boolean ruleFired = false;
				while (matcher.find()) {
					ruleFired = true;
					matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement(rule, matcher)));
				}
				if (ruleFired) {
					matcher.appendTail(buffer);
					current = buffer.toString();
					changed = true;
					if (!fired.contains(rule.name())) {
						fired.add(rule.name());
					}
				}
			}
			if (!changed) {
				break;
			}
		}

		return new RedactionResult(current, !fired.isEmpty(), List.copyOf(fired));
	}

	/**
	 * Redacts every value of a field map, keeping the order and the keys.
	 *
	 * @param fields the named text fields of a record, may be null
	 * @return the redacted map and the aggregated result flags
	 */
	public RedactedFields redactAll(Map<String, String> fields) {
		if (fields == null || fields.isEmpty()) {
			return new RedactedFields(Map.of(), false, List.of());
		}
		Map<String, String> redacted = new LinkedHashMap<>();
		List<String> rules = new ArrayList<>();
		boolean anyRedacted = false;
		for (Map.Entry<String, String> entry : fields.entrySet()) {
			RedactionResult result = redact(entry.getValue());
			redacted.put(entry.getKey(), result.value());
			anyRedacted = anyRedacted || result.redacted();
			for (String rule : result.rules()) {
				if (!rules.contains(rule)) {
					rules.add(rule);
				}
			}
		}
		return new RedactedFields(Map.copyOf(redacted), anyRedacted, List.copyOf(rules));
	}

	private String replacement(Rule rule, Matcher matcher) {
		return switch (rule.name()) {
			case "key_value" -> matcher.group(1) + "=" + REDACTED;
			case "query_parameter" -> matcher.group(1) + REDACTED;
			case "url_userinfo" -> "://" + REDACTED + "@";
			case "private_key" -> "[REDACTED PRIVATE KEY]";
			default -> REDACTED;
		};
	}

	private record Rule(String name, Pattern pattern) {
	}

	/**
	 * Result of redacting one text value.
	 *
	 * @param value the redacted value, null only when the input was null
	 * @param redacted true when at least one rule fired
	 * @param rules the names of the rules that fired, in the order they fired
	 */
	public record RedactionResult(String value, boolean redacted, List<String> rules) {
	}

	/**
	 * Result of redacting a named field map.
	 *
	 * @param values the redacted values by field name
	 * @param redacted true when at least one field was redacted
	 * @param rules the distinct rule names that fired
	 */
	public record RedactedFields(Map<String, String> values, boolean redacted, List<String> rules) {
	}
}
