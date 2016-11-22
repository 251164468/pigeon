/**
 * Dianping.com Inc.
 * Copyright (c) 2003-2013 All Rights Reserved.
 */
package com.dianping.pigeon.registry;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang.StringUtils;

import com.dianping.pigeon.config.ConfigChangeListener;
import com.dianping.pigeon.config.ConfigManager;
import com.dianping.pigeon.config.ConfigManagerLoader;
import com.dianping.pigeon.domain.HostInfo;
import com.dianping.pigeon.extension.ExtensionLoader;
import com.dianping.pigeon.log.Logger;
import com.dianping.pigeon.log.LoggerLoader;
import com.dianping.pigeon.monitor.Monitor;
import com.dianping.pigeon.monitor.MonitorLoader;
import com.dianping.pigeon.registry.exception.RegistryException;
import com.dianping.pigeon.registry.listener.RegistryEventListener;
import com.dianping.pigeon.registry.listener.ServerInfoListener;
import com.dianping.pigeon.registry.util.Constants;
import com.dianping.pigeon.registry.util.HeartBeatSupport;
import com.dianping.pigeon.registry.util.Utils;
import com.dianping.pigeon.util.VersionUtils;

public class RegistryManager {

	private static final Logger logger = LoggerLoader.getLogger(RegistryManager.class);

	private static volatile boolean isInit = false;

	private static Throwable initializeException = null;

	private static RegistryManager instance = new RegistryManager();

	private static volatile Registry registry = null;

	private static final String KEY_PIGEON_REGISTRY_CUSTOMIZED = "pigeon.registry.customized";

	private static ConcurrentHashMap<String, Set<HostInfo>> referencedServiceAddresses = new ConcurrentHashMap<String, Set<HostInfo>>();

	private static ConcurrentHashMap<String, HostInfo> referencedAddresses = new ConcurrentHashMap<String, HostInfo>();

	private static ConfigManager configManager = ConfigManagerLoader.getConfigManager();

	private static ConcurrentHashMap<String, Object> registeredServices = new ConcurrentHashMap<String, Object>();

	// host --> (service --> support)
	private static ConcurrentHashMap<String, Map<String, Boolean>> referencedServiceProtocols = new ConcurrentHashMap<String, Map<String, Boolean>>();

	Monitor monitor = MonitorLoader.getMonitor();

	public static final boolean fallbackDefaultGroup = configManager.getBooleanValue("pigeon.registry.group.fallback",
			true);

	private static boolean enableLocalConfig = ConfigManagerLoader.getConfigManager()
			.getBooleanValue("pigeon.registry.config.local", false);

	private RegistryManager() {
	}

	public static boolean isInitialized() {
		return isInit;
	}

	public static Throwable getInitializeException() {
		return initializeException;
	}

	public static RegistryManager getInstance() {
		if (!isInit) {
			synchronized (RegistryManager.class) {
				if (!isInit) {
					instance.init();
					initializeException = null;
					RegistryEventListener.addListener(new InnerServerInfoListener());
					isInit = true;
				}
			}
		}
		return instance;
	}

	private void init() {
		List<Registry> _registryList = ExtensionLoader.getExtensionList(Registry.class);
		try {
			if (_registryList.size() > 0) {
				String customizedRegistryName = configManager.getStringValue(KEY_PIGEON_REGISTRY_CUSTOMIZED,
						Constants.REGISTRY_CURATOR_NAME);
				for (Registry registry : _registryList) {
					if (registry.getName().equals(customizedRegistryName)) {
						registry.init();
						RegistryManager.registry = registry;
						logger.info(registry.getName() + " registry started.");
					}
				}
			} else {
				throw new RegistryException("failed to find registry extension type, please check dependencies!");
			}
			configManager.registerConfigChangeListener(new InnerConfigChangeListener());
		} catch (Throwable t) {
			initializeException = t;
			throw new RuntimeException(t);
		}
	}

	public Registry getRegistry() {
		return registry;
	}

	public Set<String> getReferencedServices() {
		return referencedServiceAddresses.keySet();
	}

	public Set<String> getRegisteredServices() {
		return registeredServices.keySet();
	}

	public boolean isReferencedService(String serviceName, String group) {
		return referencedServiceAddresses.containsKey(serviceName);
	}

	public List<String> getServiceAddressList(String serviceName, String group) throws RegistryException {
		String serviceAddress = getServiceAddress(serviceName, group);
		return Utils.getAddressList(serviceName, serviceAddress);
	}

	// invoker
	public String getServiceAddress(String remoteAppkey, String serviceName, String group) throws RegistryException {
		String serviceKey = getServiceKey(serviceName, group);
		if (enableLocalConfig) {
			String addr = configManager.getLocalStringValue(Utils.escapeServiceName(serviceKey));
			if (addr == null) {
				try {
					addr = configManager.getLocalStringValue(serviceKey);
				} catch (Throwable t) {
				}
			}
			if (!StringUtils.isBlank(addr)) {
				if (logger.isDebugEnabled()) {
					logger.debug("get service address from local properties, service name:" + serviceName + "  address:"
							+ addr);
				}
				return addr;
			}
		}

		if (registry != null) {
			String addr = registry.getServiceAddress(remoteAppkey, serviceName, group, fallbackDefaultGroup);
			return addr;
		}

		return "";
	}

	// invoker
	public String getServiceAddress(String serviceName, String group) throws RegistryException {
		String serviceKey = getServiceKey(serviceName, group);
		if (enableLocalConfig) {
			String addr = configManager.getLocalStringValue(Utils.escapeServiceName(serviceKey));
			if (addr == null) {
				try {
					addr = configManager.getLocalStringValue(serviceKey);
				} catch (Throwable t) {
				}
			}
			if (!StringUtils.isBlank(addr)) {
				if (logger.isDebugEnabled()) {
					logger.debug("get service address from local properties, service name:" + serviceName + "  address:"
							+ addr);
				}
				return addr;
			}
		}

		if (registry != null) {
			String addr = registry.getServiceAddress(serviceName, group, fallbackDefaultGroup);
			return addr;
		}

		return "";
	}

	private String getServiceKey(String serviceName, String group) {
		if (StringUtils.isBlank(group)) {
			return serviceName;
		} else {
			return serviceName + "?" + group;
		}
	}

	// invoker
	public int getServiceWeightFromCache(String serverAddress) {
		HostInfo hostInfo = referencedAddresses.get(serverAddress);
		if (hostInfo != null) {
			return hostInfo.getWeight();
		}
		return Constants.DEFAULT_WEIGHT;
	}

	// invoker
	public int getServiceWeight(String serverAddress, boolean readCache) {
		if (readCache) {
			HostInfo hostInfo = referencedAddresses.get(serverAddress);
			if (hostInfo != null) {
				return hostInfo.getWeight();
			}
		}
		int weight = Constants.DEFAULT_WEIGHT;

		if (registry != null) {
			try {
				weight = registry.getServerWeight(serverAddress);
				HostInfo hostInfo = referencedAddresses.get(serverAddress);
				if (hostInfo != null) {
					hostInfo.setWeight(weight);
				}
			} catch (Throwable t) {
				logger.error("failed to get weight for " + serverAddress, t);
			}
		}

		return weight;
	}

	// invoker
	public int getServiceWeight(String serverAddress) {
		return getServiceWeight(serverAddress, true);
	}

	/**
	 * For invoker to update service weight in local cache. Will not update to
	 * registry center.
	 *
	 * @param serviceAddress
	 * @param weight
	 */
	public void setServiceWeight(String serviceAddress, int weight) {
		HostInfo hostInfo = referencedAddresses.get(serviceAddress);
		if (hostInfo == null) {
			if (!serviceAddress.startsWith(configManager.getLocalIp())) {
				logger.warn("weight not found for address:" + serviceAddress);
			}
			return;
		}
		hostInfo.setWeight(weight);
		logger.info("set " + serviceAddress + " weight to " + weight);
	}

	// provider
	public void registerService(String serviceName, String group, String serviceAddress, int weight)
			throws RegistryException {
		if (registry != null) {
			registry.registerService(serviceName, group, serviceAddress, weight);
			registeredServices.putIfAbsent(serviceName, serviceAddress);
			monitor.logEvent("PigeonService.register", serviceName, "weight=" + weight + "&group=" + group);
		}
	}

	// provider
	public void setServerWeight(String serverAddress, int weight) throws RegistryException {
		if (registry != null) {
			registry.setServerWeight(serverAddress, weight);
			monitor.logEvent("PigeonService.weight", weight + "", "");
		}
	}

	// provider
	public void unregisterService(String serviceName, String serviceAddress) throws RegistryException {
		unregisterService(serviceName, Constants.DEFAULT_GROUP, serviceAddress);
	}

	// provider
	public void unregisterService(String serviceName, String group, String serviceAddress) throws RegistryException {
		if (registry != null) {
			registry.unregisterService(serviceName, group, serviceAddress);
			registeredServices.remove(serviceName);
			monitor.logEvent("PigeonService.unregister", serviceName, "group=" + group);
		}
	}

	// invoker
	public void addServiceAddress(String serviceName, String host, int port, int weight) {
		Utils.validateWeight(host, port, weight);

		Set<HostInfo> hostInfos = referencedServiceAddresses.get(serviceName);

		if (hostInfos == null) {
			hostInfos = Collections.newSetFromMap(new ConcurrentHashMap<HostInfo, Boolean>());
			Set<HostInfo> oldHostInfos = referencedServiceAddresses.putIfAbsent(serviceName, hostInfos);
			if (oldHostInfos != null) {
				hostInfos = oldHostInfos;
			}
		}

		HostInfo hostInfo = new HostInfo(host, port, weight);
		hostInfos.remove(hostInfo);
		hostInfos.add(hostInfo);
		String serviceAddress = hostInfo.getConnect();

		// 添加服务端是否支持新协议的缓存
		Map<String, Boolean> protocolInfoMap = referencedServiceProtocols.get(serviceAddress);
		if (protocolInfoMap == null) {
			protocolInfoMap = new ConcurrentHashMap<String, Boolean>();
			Map<String, Boolean> oldProtocolInfoMap = referencedServiceProtocols.putIfAbsent(serviceAddress,
					protocolInfoMap);
			if (oldProtocolInfoMap != null) {
				protocolInfoMap = oldProtocolInfoMap;
			}
		}
		// invoker读取注册中心的协议信息并且put进去
		boolean support = false;
		try {
			support = isSupportNewProtocol(serviceAddress, serviceName, false);
		} catch (RegistryException e) {
			logger.info(e.getMessage(), e);
		}
		protocolInfoMap.put(serviceName, support);

		if (!referencedAddresses.containsKey(serviceAddress)) {
			referencedAddresses.put(serviceAddress, hostInfo);
			if (registry != null) {

				try {
					String app = registry.getServerApp(hostInfo.getConnect());
					hostInfo.setApp(app);
				} catch (RegistryException e) {
					logger.info("failed to update app in cache for: " + serviceAddress);
				}

				try {
					String version = registry.getServerVersion(hostInfo.getConnect());
					hostInfo.setVersion(version);
				} catch (RegistryException e) {
					logger.info("failed to update version in cache for: " + serviceAddress);
				}

				try {
					byte heartBeatSupport = registry.getServerHeartBeatSupport(hostInfo.getConnect());
					hostInfo.setHeartBeatSupport(heartBeatSupport);
				} catch (RegistryException e) {
					logger.info("failed to update heartBeatSupport in cache for: " + serviceAddress);
				}

			}
		}
	}

	public void removeServiceAddress(String serviceName, HostInfo hostInfo) {
		Set<HostInfo> hostInfos = referencedServiceAddresses.get(serviceName);
		if (hostInfos == null || !hostInfos.contains(hostInfo)) {
			logger.info("address:" + hostInfo + " is not in address list of service " + serviceName);
			return;
		}
		hostInfos.remove(hostInfo);
		logger.info("removed address:" + hostInfo + " from service:" + serviceName);

		// If server is not referencd any more, remove from server list
		if (!isAddressReferenced(hostInfo)) {
			referencedAddresses.remove(hostInfo.getConnect());
		}
	}

	private boolean isAddressReferenced(HostInfo hostInfo) {
		for (String key : referencedServiceAddresses.keySet()) {
			Set<HostInfo> hostInfos = referencedServiceAddresses.get(key);
			if (hostInfos.contains(hostInfo)) {
				logger.info("address:" + hostInfo + " still been referenced for service:" + key);
				return true;
			}
		}
		return false;
	}

	public Set<HostInfo> getReferencedServiceAddresses(String serviceName) {
		Set<HostInfo> hostInfos = referencedServiceAddresses.get(serviceName);
		if (hostInfos == null || hostInfos.size() == 0) {
			logger.info("empty address list for service:" + serviceName);
		}
		return hostInfos;
	}

	public Map<String, Set<HostInfo>> getAllReferencedServiceAddresses() {
		return referencedServiceAddresses;
	}

	public String getReferencedAppFromCache(String serverAddress) {
		HostInfo hostInfo = referencedAddresses.get(serverAddress);
		String app = "";

		if (hostInfo != null) {
			app = hostInfo.getApp();
		}

		return app;
	}

	public Map<String, Boolean> getProtocolInfoFromCache(String serviceAddress) {
		Map<String, Boolean> protocolInfoMap = referencedServiceProtocols.get(serviceAddress);
		if (protocolInfoMap != null) {
			return protocolInfoMap;
		}

		return new HashMap<String, Boolean>();
	}

	public boolean isSupportNewProtocolFromCache(String serviceAddress, String serviceName) {
		Map<String, Boolean> protocolInfoMap = referencedServiceProtocols.get(serviceAddress);
		if (protocolInfoMap != null && protocolInfoMap.containsKey(serviceName)) {
			return protocolInfoMap.get(serviceName);
		}

		return false;
	}

	public String getReferencedApp(String serverAddress) {
		String app = "";

		if (registry != null) {
			try {
				app = registry.getServerApp(serverAddress);
				setReferencedApp(serverAddress, app);
			} catch (RegistryException e) {
				logger.info("failed to update app in cache for: " + serverAddress);
			}
		}

		return app;
	}

	public void setReferencedApp(String serverAddress, String app) {
		HostInfo hostInfo = referencedAddresses.get(serverAddress);
		if (hostInfo != null) {
			hostInfo.setApp(app);
		}
	}

	public void setServerApp(String serverAddress, String app) {
		if (registry != null) {
			registry.setServerApp(serverAddress, app);
		}
	}

	public void unregisterServerApp(String serverAddress) {
		if (registry != null) {
			registry.unregisterServerApp(serverAddress);
		}
	}

	public String getReferencedVersionFromCache(String serverAddress) {
		HostInfo hostInfo = referencedAddresses.get(serverAddress);
		String version = null;
		if (hostInfo != null) {
			version = hostInfo.getVersion();
			// if (version == null && registry != null) {
			// version = registry.getServerVersion(serverAddress);
			// hostInfo.setVersion(version);
			// }
			return version;
		}
		return null;
	}

	public String getReferencedVersion(String serverAddress) {
		String version = "";
		if (registry != null) {
			try {
				version = registry.getServerVersion(serverAddress);
				setReferencedVersion(serverAddress, version);
			} catch (RegistryException e) {
				logger.info("failed to update version in cache for: " + serverAddress);
			}
		}
		return version;
	}

	public void setReferencedVersion(String serverAddress, String version) {
		HostInfo hostInfo = referencedAddresses.get(serverAddress);
		if (hostInfo != null) {
			hostInfo.setVersion(version);
		}
	}

	public void setServerVersion(String serverAddress, String version) {
		if (registry != null) {
			registry.setServerVersion(serverAddress, version);
		}
	}

	public void unregisterServerVersion(String serverAddress) {
		if (registry != null) {
			registry.unregisterServerVersion(serverAddress);
		}
	}

	static class InnerServerInfoListener implements ServerInfoListener {

		@Override
		public void onServerAppChange(String serverAddress, String app) {
			HostInfo hostInfo = referencedAddresses.get(serverAddress);
			if (hostInfo != null) {
				hostInfo.setApp(app);
			}
		}

		@Override
		public void onServerVersionChange(String serverAddress, String version) {
			HostInfo hostInfo = referencedAddresses.get(serverAddress);
			if (hostInfo != null) {
				hostInfo.setVersion(version);
			}
		}

		@Override
		public void onServerProtocolChange(String serverAddress, Map<String, Boolean> protocolInfoMap) {
			// 更新invoker缓存的服务端协议详情
			referencedServiceProtocols.put(serverAddress, protocolInfoMap);
		}

		@Override
		public void onServerHeartBeatSupportChange(String serverAddress, byte heartBeatSupport) {
			HostInfo hostInfo = referencedAddresses.get(serverAddress);
			if (hostInfo != null) {
				hostInfo.setHeartBeatSupport(heartBeatSupport);
			}
		}

	}

	public void updateHeartBeat(String serviceAddress, Long heartBeatTimeMillis) {
		if (registry != null) {
			registry.updateHeartBeat(serviceAddress, heartBeatTimeMillis);
		}
	}

	public void deleteHeartBeat(String serviceAddress) {
		if (registry != null) {
			registry.deleteHeartBeat(serviceAddress);
		}
	}

	// invoker
	public byte getServerHeartBeatSupportFromCache(String serverAddress) {
		HostInfo hostInfo = referencedAddresses.get(serverAddress);
		byte heartBeatSupport;

		if (hostInfo != null) {
			heartBeatSupport = hostInfo.getHeartBeatSupport();
			return heartBeatSupport;
		}

		return HeartBeatSupport.BOTH.getValue();
	}

	// invoker
	public byte getServerHeartBeatSupport(String serviceAddress) {
		byte heartBeatSupport = HeartBeatSupport.BOTH.getValue();

		if (registry != null) {
			try {
				heartBeatSupport = registry.getServerHeartBeatSupport(serviceAddress);
				setServerHeartBeatSupport(serviceAddress, heartBeatSupport);
			} catch (RegistryException e) {
				logger.info("failed to update heartBeatSupport in cache for: " + serviceAddress);
			}
		}

		return heartBeatSupport;
	}

	// invoker
	public void setServerHeartBeatSupport(String serverAddress, byte heartBeatSupport) {
		HostInfo hostInfo = referencedAddresses.get(serverAddress);
		if (hostInfo != null) {
			hostInfo.setHeartBeatSupport(heartBeatSupport);
		}
	}

	public boolean isSupportNewProtocol(String serviceAddress) throws RegistryException {
		// 1. load from cache
		String version = getReferencedVersionFromCache(serviceAddress);

		if (version != null) {
			return VersionUtils.isThriftSupported(version);
		}

		// 2. load from registry
		boolean support = false;

		try {
			support = registry.isSupportNewProtocol(serviceAddress);
		} catch (Throwable t) {
			logger.info("failed to get protocol for " + serviceAddress, t.getMessage());
		}

		return support;
	}

	public boolean isSupportNewProtocol(String serviceAddress, String serviceName) throws RegistryException {
		return isSupportNewProtocol(serviceAddress, serviceName, true);
	}

	public boolean isSupportNewProtocol(String serviceAddress, String serviceName, boolean readCache)
			throws RegistryException {
		if (readCache) {
			Map<String, Boolean> protocolInfoMap = referencedServiceProtocols.get(serviceAddress);
			if (protocolInfoMap != null && protocolInfoMap.containsKey(serviceName)) {
				return protocolInfoMap.get(serviceName);
			}
		}

		boolean support = false;

		try {
			support = registry.isSupportNewProtocol(serviceAddress, serviceName);
			Map<String, Boolean> protocolInfoMap = referencedServiceProtocols.get(serviceAddress);

			if (protocolInfoMap != null) {
				protocolInfoMap.put(serviceName, support);
			}
		} catch (Throwable t) {
			logger.info("failed to get protocol for " + serviceAddress + "#" + serviceName);
            throw new RegistryException(t);
		}

		return support;
	}

	/**
	 * For provider to register protocol to registry center.
	 *
	 * @param serviceAddress
	 * @param serviceName
	 * @param support
	 * @throws RegistryException
	 */
	public void registerSupportNewProtocol(String serviceAddress, String serviceName, boolean support)
			throws RegistryException {
		if (registry != null) {
			registry.setSupportNewProtocol(serviceAddress, serviceName, support);
		}
		monitor.logEvent("PigeonService.protocol", serviceName, "support=" + support);
	}

	public void unregisterSupportNewProtocol(String serviceAddress, String serviceName, boolean support)
			throws RegistryException {
		if (registry != null) {
			registry.unregisterSupportNewProtocol(serviceAddress, serviceName, support);
		}
		monitor.logEvent("PigeonService.protocol", serviceName, "unregister");
	}

	public boolean getReferencedProtocol(String serverAddress, String serviceName) {
		boolean support = false;

		try {
			support = registry.isSupportNewProtocol(serverAddress, serviceName);
			setReferencedProtocol(serverAddress, serviceName, support);
		} catch (Throwable t) {
			logger.info("failed to get protocol for " + serverAddress + "#" + serviceName, t.getMessage());
		}

		return support;
	}

	private void setReferencedProtocol(String serverAddress, String serviceName, boolean support) {
		Map<String, Boolean> infoMap = referencedServiceProtocols.get(serverAddress);
		if (infoMap != null) {
			infoMap.put(serviceName, support);
		}
	}

	/**
	 * for governor: manual update service and set weight to 1
	 *
	 * @param serviceName
	 * @param group
	 * @param hosts
	 * @author chenchongze
	 */
	public void setServerService(String serviceName, String group, String hosts) throws RegistryException {
		if (registry != null) {
			registry.setServerService(serviceName, group, hosts);
		}

		monitor.logEvent("PigeonGovernor.setHosts", serviceName, "swimlane=" + group + "&hosts=" + hosts);
	}

	public void setHostsWeight(String serviceName, String group, String hosts, int weight) throws RegistryException {
		if (registry != null) {
			registry.setHostsWeight(serviceName, group, hosts, weight);
		}

		monitor.logEvent("PigeonGovernor.setWeight", hosts, weight + "");
	}

	/**
	 * for governor: manual delete service
	 *
	 * @param serviceName
	 * @param group
	 * @throws RegistryException
	 */
	public void delServerService(String serviceName, String group) throws RegistryException {
		if (registry != null) {
			registry.delServerService(serviceName, group);
		}

		monitor.logEvent("PigeonGovernor.delService", serviceName, "swimlane=" + group);
	}

	/**
	 * for governor: getServiceHosts from zk
	 *
	 * @param serviceName
	 * @param group
	 * @return
	 * @throws RegistryException
	 */
	public String getServiceHosts(String serviceName, String group) throws RegistryException {
		String addr = "";

		try {
			addr = registry.getServiceAddress(serviceName, group, false, false);
		} catch (Throwable t) {
			logger.info("failed to get service hosts for " + serviceName + "#" + group + ", msg: " + t.getMessage());

			throw new RegistryException(t);
		}

		return addr;
	}

	private class InnerConfigChangeListener implements ConfigChangeListener {

		@Override
		public void onKeyUpdated(String key, String value) {
			if (key.endsWith(KEY_PIGEON_REGISTRY_CUSTOMIZED)) {
				try {

					for (Registry registry : ExtensionLoader.getExtensionList(Registry.class)) {
						if (registry.getName().equals(value)) {
							registry.init();
							RegistryManager.registry = registry;
							logger.info("change to registry: " + value);
						}
					}

				} catch (Throwable t) {
					logger.error(t);
				}
			}
		}

		@Override
		public void onKeyAdded(String key, String value) {

		}

		@Override
		public void onKeyRemoved(String key) {

		}
	}

}
