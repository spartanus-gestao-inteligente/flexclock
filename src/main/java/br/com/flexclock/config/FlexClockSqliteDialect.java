package br.com.flexclock.config;

import java.sql.Types;

import org.hibernate.community.dialect.SQLiteDialect;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;

/**
 * SQLite dialect of Flex Clock, derived from the community dialect.
 *
 * <p>SQLite auto assigns a value only to a column declared exactly as {@code INTEGER PRIMARY KEY},
 * because that declaration is what makes the column an alias of the rowid. The community dialect
 * therefore creates an identity column as {@code integer}. The entities declare their identifiers as
 * {@code Long}, so Hibernate expects {@code bigint} and rejects, during validation, the very schema it
 * would create itself.
 *
 * <p>This dialect states that the two integral types are equivalent when the schema is validated. The
 * rowid identity, the {@code Long} identifiers and {@code ddl-auto=validate} then agree, without a
 * column definition override in any entity and without losing the single writer identity sequence.
 *
 * <p>Only the type comparison of the schema validator is affected. The SQL generated at runtime, the
 * declared column types and the dialect version handling are inherited unchanged (architecture
 * contract, sections 4.5 and 11.1; decisions 0017, 0049).
 */
public class FlexClockSqliteDialect extends SQLiteDialect {

	/**
	 * Creates the dialect for the configured database version.
	 *
	 * @param resolutionInfo the version information resolved from the connection
	 */
	public FlexClockSqliteDialect(DialectResolutionInfo resolutionInfo) {
		super(resolutionInfo);
	}

	/**
	 * Creates the dialect with the default SQLite version.
	 */
	public FlexClockSqliteDialect() {
		super();
	}

	@Override
	public boolean equivalentTypes(int typeCode1, int typeCode2) {
		return isRowidIdentity(typeCode1, typeCode2) || super.equivalentTypes(typeCode1, typeCode2);
	}

	private static boolean isRowidIdentity(int typeCode1, int typeCode2) {
		return (typeCode1 == Types.BIGINT && typeCode2 == Types.INTEGER)
				|| (typeCode1 == Types.INTEGER && typeCode2 == Types.BIGINT);
	}
}
