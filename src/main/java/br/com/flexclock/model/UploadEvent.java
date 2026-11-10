package br.com.flexclock.model;

import java.time.Instant;

import br.com.flexclock.enums.EventResult;
import br.com.flexclock.enums.ReportRecordType;
import br.com.flexclock.enums.WriteSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Upload performed by an agent: destination, item, type, size, result and whether something was
 * actually sent. Append only.
 *
 * <p>Decision 0033.
 */
@Entity
@Table(name = "upload_event")
@Getter
@Setter
public class UploadEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	@Column(name = "agent_id", nullable = false, length = 128)
	private String agentId;

	@Column(name = "procedure_id")
	private Long procedureId;

	@Column(name = "session_id", length = 128)
	private String sessionId;

	@Column(name = "destination", nullable = false, length = 1000)
	private String destination;

	@Column(name = "item", nullable = false, length = 1000)
	private String item;

	@Column(name = "item_type", nullable = false, length = 128)
	private String itemType;

	@Column(name = "size_bytes", nullable = false)
	private Long sizeBytes;

	@Column(name = "result", nullable = false, length = 16)
	private EventResult result;

	@Column(name = "sent", nullable = false)
	private boolean sent;

	@Column(name = "redacted", nullable = false)
	private boolean redacted;

	@Column(name = "record_type", nullable = false, length = 32)
	private ReportRecordType recordType;

	@Column(name = "write_source", nullable = false, length = 32)
	private WriteSource writeSource;

	@Column(name = "idempotency_key", nullable = false, unique = true, length = 128)
	private String idempotencyKey;

	protected UploadEvent() {
		// Required by JPA.
	}
}
