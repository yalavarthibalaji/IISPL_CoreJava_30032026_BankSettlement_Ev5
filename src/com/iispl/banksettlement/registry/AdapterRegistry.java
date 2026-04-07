package com.iispl.banksettlement.registry;

import com.iispl.banksettlement.adapter.CbsAdapter;
import com.iispl.banksettlement.adapter.FintechAdapter;
import com.iispl.banksettlement.adapter.NeftAdapter;
import com.iispl.banksettlement.adapter.RtgsAdapter;
import com.iispl.banksettlement.adapter.TransactionAdapter;
import com.iispl.banksettlement.adapter.UpiAdapter;
import com.iispl.banksettlement.enums.SourceType;

import java.util.HashMap;
import java.util.Map;

/**
 * AdapterRegistry — holds one TransactionAdapter per SourceType.
 *
 * CHANGE LOG (v2): - SwiftAdapter removed. SWIFT (cross-border) is no longer a
 * supported source system. This system now handles Indian domestic banks only.
 * Registered sources: CBS, RTGS, NEFT, UPI, FINTECH.
 */
public class AdapterRegistry {

	private final Map<SourceType, TransactionAdapter> adapterMap;

	public AdapterRegistry() {
		adapterMap = new HashMap<>();
		registerAdapter(new CbsAdapter());
		registerAdapter(new RtgsAdapter());
		registerAdapter(new NeftAdapter());
		registerAdapter(new UpiAdapter());
		registerAdapter(new FintechAdapter());

		System.out.println("AdapterRegistry: Registered adapters for: " + adapterMap.keySet());
	}

	public void registerAdapter(TransactionAdapter adapter) {
		if (adapter == null) {
			throw new IllegalArgumentException("AdapterRegistry: Cannot register a null adapter");
		}
		adapterMap.put(adapter.getSourceType(), adapter);
	}

	public TransactionAdapter getAdapter(SourceType sourceType) {
		if (sourceType == null) {
			throw new IllegalArgumentException("AdapterRegistry: sourceType cannot be null");
		}
		TransactionAdapter adapter = adapterMap.get(sourceType);
		if (adapter == null) {
			throw new IllegalArgumentException("AdapterRegistry: No adapter registered for SourceType: " + sourceType
					+ ". Registered types are: " + adapterMap.keySet());
		}
		return adapter;
	}

	public boolean hasAdapter(SourceType sourceType) {
		return sourceType != null && adapterMap.containsKey(sourceType);
	}

	public int getRegisteredCount() {
		return adapterMap.size();
	}

	@Override
	public String toString() {
		return "AdapterRegistry{registeredSources=" + adapterMap.keySet() + "}";
	}
}