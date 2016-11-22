/**
 * Dianping.com Inc.
 * Copyright (c) 2003-${year} All Rights Reserved.
 */
package com.dianping.pigeon.remoting.provider.process.filter;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang.StringUtils;

import com.dianping.pigeon.config.ConfigChangeListener;
import com.dianping.pigeon.config.ConfigManager;
import com.dianping.pigeon.config.ConfigManagerLoader;
import com.dianping.pigeon.log.Logger;
import com.dianping.pigeon.log.LoggerLoader;
import com.dianping.pigeon.remoting.common.domain.InvocationContext.TimePhase;
import com.dianping.pigeon.remoting.common.domain.InvocationContext.TimePoint;
import com.dianping.pigeon.remoting.common.domain.InvocationRequest;
import com.dianping.pigeon.remoting.common.domain.InvocationResponse;
import com.dianping.pigeon.remoting.common.domain.generic.UnifiedRequest;
import com.dianping.pigeon.remoting.common.exception.SecurityException;
import com.dianping.pigeon.remoting.common.process.ServiceInvocationFilter;
import com.dianping.pigeon.remoting.common.process.ServiceInvocationHandler;
import com.dianping.pigeon.remoting.common.util.Constants;
import com.dianping.pigeon.remoting.common.util.ContextUtils;
import com.dianping.pigeon.remoting.common.util.SecurityUtils;
import com.dianping.pigeon.remoting.provider.domain.ProviderContext;
import com.dianping.pigeon.util.TimeUtils;

/**
 * @author xiangwu
 */
public class SecurityFilter implements ServiceInvocationFilter<ProviderContext> {

	private static final Logger logger = LoggerLoader.getLogger(SecurityFilter.class);
	private static final ConfigManager configManager = ConfigManagerLoader.getConfigManager();
	private static final String KEY_APP_SECRETS = "pigeon.provider.token.app.secrets";
	private static final String KEY_TOKEN_ENABLE = "pigeon.provider.token.enable";
	private static final String KEY_TOKEN_SWITCHES = "pigeon.provider.token.switches";
	private static final String KEY_ACCESS_IP_ENABLE = "pigeon.provider.access.ip.enable";
	private static final String KEY_TOKEN_PROTOCOL_DEFAULT_ENABLE = "pigeon.provider.token.protocol.default.enable";

	private static final String KEY_TOKEN_TIMESTAMP_DIFF = "pigeon.provider.token.timestamp.diff";
	private static volatile ConcurrentHashMap<String, String> appSecrets = new ConcurrentHashMap<String, String>();
	private static volatile ConcurrentHashMap<String, Boolean> tokenSwitches = new ConcurrentHashMap<String, Boolean>();

	private static volatile Set<String> ipBlackSet = Collections
			.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

	private static volatile Set<String> ipWhiteSet = Collections
			.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

	private static final String KEY_BLACKLIST = "pigeon.provider.access.ip.blacklist";
	private static final String KEY_WHITELIST = "pigeon.provider.access.ip.whitelist";
	private static final String DEFAULT_VALUE_WHITELIST = "127.0.0.1,";
	private static final String KEY_ACCESS_DEFAULT = "pigeon.provider.access.ip.default";

	public SecurityFilter() {
		configManager.getBooleanValue(KEY_TOKEN_ENABLE, false);
		configManager.getBooleanValue(KEY_TOKEN_PROTOCOL_DEFAULT_ENABLE, false);
		configManager.getIntValue(KEY_TOKEN_TIMESTAMP_DIFF, 120);
		configManager.getBooleanValue(KEY_ACCESS_DEFAULT, true);
		configManager.getBooleanValue(KEY_ACCESS_IP_ENABLE, false);
		parseBlackList(configManager.getStringValue(KEY_BLACKLIST, ""));
		parseWhiteList(configManager.getStringValue(KEY_WHITELIST, DEFAULT_VALUE_WHITELIST));
		parseAppSecrets(configManager.getStringValue(KEY_APP_SECRETS, ""));
		parseTokenSwitchesConfig(configManager.getStringValue(KEY_TOKEN_SWITCHES, ""));
		ConfigManagerLoader.getConfigManager().registerConfigChangeListener(new InnerConfigChangeListener());
	}

	private static void parseBlackList(String config) {
		String[] blackArray = config.split(",");
		Set<String> set = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
		for (String addr : blackArray) {
			if (StringUtils.isBlank(addr)) {
				continue;
			}
			set.add(addr.trim());
		}
		ipBlackSet = set;
	}

	private static void parseWhiteList(String config) {
		String[] whiteArray = config.split(",");
		Set<String> set = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
		for (String addr : whiteArray) {
			if (StringUtils.isBlank(addr)) {
				continue;
			}
			set.add(addr.trim());
		}
		ipWhiteSet = set;
	}

	private static boolean canAccess(String ip) {
		if (configManager.getBooleanValue(KEY_ACCESS_IP_ENABLE, false)) {
			for (String addr : ipWhiteSet) {
				if (ip.startsWith(addr)) {
					return true;
				}
			}
			for (String addr : ipBlackSet) {
				if (ip.startsWith(addr)) {
					return false;
				}
			}
			return configManager.getBooleanValue(KEY_ACCESS_DEFAULT, true);
		}
		return true;
	}

	private static void parseAppSecrets(String config) {
		if (StringUtils.isNotBlank(config)) {
			ConcurrentHashMap<String, String> map = new ConcurrentHashMap<String, String>();
			try {
				String[] pairArray = config.split(",");
				for (String str : pairArray) {
					if (StringUtils.isNotBlank(str)) {
						String[] pair = str.split(":");
						if (pair != null && pair.length == 2) {
							String app = pair[0].trim();
							String secret = pair[1].trim();
							if (secret.length() < 16) {
								throw new IllegalArgumentException("Secret length must not be less than 16");
							}
							map.put(app, secret);
						}
					}
				}
				appSecrets.clear();
				appSecrets = map;
			} catch (RuntimeException e) {
				logger.error("error while parsing app secret configuration:" + config, e);
			}
		} else {
			appSecrets.clear();
		}
	}

	private static class InnerConfigChangeListener implements ConfigChangeListener {

		@Override
		public void onKeyUpdated(String key, String value) {
			if (key.endsWith(KEY_APP_SECRETS)) {
				parseAppSecrets(value);
			} else if (key.endsWith(KEY_BLACKLIST)) {
				parseBlackList(value);
			} else if (key.endsWith(KEY_WHITELIST)) {
				parseWhiteList(value);
			} else if (key.endsWith(KEY_TOKEN_SWITCHES)) {
				parseTokenSwitchesConfig(value);
			}
		}

		@Override
		public void onKeyAdded(String key, String value) {

		}

		@Override
		public void onKeyRemoved(String key) {

		}

	}

	private static int getCurrentTime() {
		return (int) (TimeUtils.currentTimeMillis() / 1000);
	}

	public static void authenticateRequestIp(String remoteAddress) {
		if (!canAccess(remoteAddress)) {
			throw new SecurityException("Request ip:" + remoteAddress + " is not allowed");
		}
	}

	public static void authenticateRequestToken(String app, String remoteAddress, String timestamp, String version,
			String token, String serviceName, String methodName) {
		if (needValidateToken(serviceName, methodName)) {
			doAuthenticateRequestToken(app, remoteAddress, timestamp, version, token, serviceName, methodName);
		}
	}

	private static void doAuthenticateRequestToken(String app, String remoteAddress, String timestamp, String version,
			String token, String serviceName, String methodName) {
		if (StringUtils.isBlank(app)) {
			throw new SecurityException("Request app is required, from:" + remoteAddress);
		}
		String secret = appSecrets.get(app);
		if (StringUtils.isNotBlank(secret)) {
			if (StringUtils.isBlank(token)) {
				throw new SecurityException("Request token is required, from:" + remoteAddress + "@" + app);
			}
			int time = 0;
			try {
				time = Integer.parseInt(timestamp);
			} catch (RuntimeException e) {
			}
			if (time <= 0) {
				throw new SecurityException(
						"Request timestamp is invalid:" + timestamp + ", from:" + remoteAddress + "@" + app);
			}
			long timediff = getCurrentTime() - time;
			if (Math.abs(timediff) > configManager.getIntValue(KEY_TOKEN_TIMESTAMP_DIFF, 120)) {
				throw new SecurityException("The request has expired:" + timestamp + ", from:" + app);
			}
			String data = serviceName + "#" + methodName + "#" + time;
			String expectToken = SecurityUtils.encrypt(data, secret);
			if (!expectToken.equals(token)) {
				throw new SecurityException("Invalid request token:" + token + ", from:" + remoteAddress + "@" + app);
			}
		} else {
			throw new SecurityException("Secret not found for app:" + app);
		}
	}

	private static void parseTokenSwitchesConfig(String config) {
		ConcurrentHashMap<String, Boolean> map = new ConcurrentHashMap<String, Boolean>();
		String[] pairArray = config.split(",");
		for (String str : pairArray) {
			if (StringUtils.isNotBlank(str)) {
				String[] pair = str.split("=");
				if (pair != null && pair.length == 2) {
					String key = pair[0].trim();
					String value = pair[1].trim();
					if (StringUtils.isNotBlank(key) && StringUtils.isNotBlank(value)) {
						try {
							map.put(key, Boolean.valueOf(value));
						} catch (RuntimeException e) {
						}
					}
				}
			}
		}
		ConcurrentHashMap<String, Boolean> old = tokenSwitches;
		tokenSwitches = map;
		old.clear();
	}

	private static boolean needValidateToken(String serviceName, String methodName) {
		if (configManager.getBooleanValue(KEY_TOKEN_ENABLE, false)) {
			if (!tokenSwitches.isEmpty()) {
				Boolean enable = tokenSwitches.get(serviceName + "#" + methodName);
				if (enable != null) {
					return enable;
				} else {
					enable = tokenSwitches.get(serviceName);
					if (enable != null) {
						return enable;
					}
				}
			}
			return true;
		}
		return false;
	}

	@Override
	public InvocationResponse invoke(ServiceInvocationHandler handler, ProviderContext invocationContext)
			throws Throwable {
		String remoteAddress = invocationContext.getChannel().getRemoteAddress();
		authenticateRequestIp(remoteAddress);

		if (needValidateToken(invocationContext.getRequest().getServiceName(),
				invocationContext.getRequest().getMethodName())) {
			invocationContext.getTimeline().add(new TimePoint(TimePhase.A));
			InvocationRequest request = invocationContext.getRequest();
			if (request.getMessageType() == Constants.MESSAGE_TYPE_SERVICE) {
				boolean isAuth = false;
				String from = (String) ContextUtils.getLocalContext("RequestIp");
				if (from == null) {
					isAuth = true;
				}
				if (!configManager.getBooleanValue(KEY_TOKEN_PROTOCOL_DEFAULT_ENABLE, false)
						&& Constants.PROTOCOL_DEFAULT.equals(invocationContext.getChannel().getProtocol())) {
					isAuth = false;
				}
				if (isAuth) {
					authenticateRequestToken(request, invocationContext);
				}
			}
		}
		return handler.handle(invocationContext);
	}

	private void authenticateRequestToken(InvocationRequest request, ProviderContext invocationContext) {
		String remoteAddress = invocationContext.getChannel().getRemoteAddress();
		String token = null;
		String timestamp = null;
		String version = null;
		if (request instanceof UnifiedRequest) {
			UnifiedRequest _request = (UnifiedRequest) request;
			Map<String, String> localContext = _request.getLocalContext();
			if (localContext != null) {
				token = localContext.get(Constants.REQUEST_KEY_TOKEN);
				if (localContext.containsKey(Constants.REQUEST_KEY_TIMESTAMP)) {
					timestamp = localContext.get(Constants.REQUEST_KEY_TIMESTAMP);
				}
				if (localContext.containsKey(Constants.REQUEST_KEY_VERSION)) {
					version = localContext.get(Constants.REQUEST_KEY_VERSION);
				}
			}
		} else {
			Map<String, Serializable> requestValues = request.getRequestValues();
			if (requestValues != null) {
				token = (String) requestValues.get(Constants.REQUEST_KEY_TOKEN);
				if (requestValues.containsKey(Constants.REQUEST_KEY_TIMESTAMP)) {
					timestamp = requestValues.get(Constants.REQUEST_KEY_TIMESTAMP).toString();
				}
				if (requestValues.containsKey(Constants.REQUEST_KEY_VERSION)) {
					version = requestValues.get(Constants.REQUEST_KEY_VERSION).toString();
				}
			}
		}
		doAuthenticateRequestToken(request.getApp(), remoteAddress, timestamp, version, token, request.getServiceName(),
				request.getMethodName());
	}

}
