package com.tomaszrup.groovyls;

/**
 * Shared constants for custom extension↔server protocol messages.
 */
public final class Protocol {

	private Protocol() {
	}

	/**
	 * Custom protocol contract version between the VS Code extension and server.
	 */
	public static final String VERSION = "1";

	public static final String REQUEST_GET_DECOMPILED_CONTENT = "groovy/getDecompiledContent";
	public static final String REQUEST_GET_PROTOCOL_VERSION = "groovy/getProtocolVersion";

	public static final String NOTIFICATION_STATUS_UPDATE = "groovy/statusUpdate";
	public static final String NOTIFICATION_MEMORY_USAGE = "groovy/memoryUsage";
}
