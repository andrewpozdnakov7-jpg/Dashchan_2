package com.mishiranu.dashchan.content;

import java.util.Objects;

/** Default-network event ordering, independent of Android and the calling thread. */
final class DefaultNetworkState<N, S> {
	private final S disconnected;
	private volatile S snapshot;
	private N network;
	private long revision;

	DefaultNetworkState(S disconnected) {
		this.disconnected = disconnected;
	}

	S get() { return snapshot; }
	synchronized long revision() { return revision; }

	synchronized void available(N network) {
		revision++;
		if (!Objects.equals(this.network, network)) {
			this.network = network;
			snapshot = null; // Await the new network's capabilities; never keep the old VPN/Wi-Fi flags.
		}
	}

	synchronized boolean capabilities(N network, S snapshot) {
		if (!Objects.equals(this.network, network)) return false;
		revision++;
		this.snapshot = snapshot;
		return true;
	}

	synchronized boolean lost(N network) {
		if (!Objects.equals(this.network, network)) return false;
		revision++;
		this.network = null;
		snapshot = disconnected;
		return true;
	}

	synchronized boolean initialize(long expectedRevision, N network, S snapshot) {
		if (revision != expectedRevision || this.snapshot != null
				|| this.network != null && !Objects.equals(this.network, network)) return false;
		revision++;
		this.network = network;
		this.snapshot = snapshot;
		return true;
	}
}
