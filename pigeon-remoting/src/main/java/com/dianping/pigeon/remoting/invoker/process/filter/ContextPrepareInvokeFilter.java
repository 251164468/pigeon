/**
 * Dianping.com Inc.
 * Copyright (c) 2003-2013 All Rights Reserved.
 */
package com.dianping.pigeon.remoting.invoker.process.filter;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.dianping.pigeon.remoting.common.codec.SerializerType;
import org.apache.commons.lang.StringUtils;

import com.dianping.pigeon.config.ConfigManagerLoader;
import com.dianping.pigeon.extension.ExtensionLoader;
import com.dianping.pigeon.log.Logger;
import com.dianping.pigeon.log.LoggerLoader;
import com.dianping.pigeon.monitor.MonitorLoader;
import com.dianping.pigeon.monitor.MonitorTransaction;
import com.dianping.pigeon.registry.RegistryManager;
import com.dianping.pigeon.registry.exception.RegistryException;
import com.dianping.pigeon.remoting.common.domain.CompactRequest;
import com.dianping.pigeon.remoting.common.domain.InvocationContext.TimePhase;
import com.dianping.pigeon.remoting.common.domain.InvocationContext.TimePoint;
import com.dianping.pigeon.remoting.common.domain.InvocationRequest;
import com.dianping.pigeon.remoting.common.domain.InvocationResponse;
import com.dianping.pigeon.remoting.common.domain.generic.UnifiedRequest;
import com.dianping.pigeon.remoting.common.process.ServiceInvocationHandler;
import com.dianping.pigeon.remoting.common.util.Constants;
import com.dianping.pigeon.remoting.common.util.ContextUtils;
import com.dianping.pigeon.remoting.common.util.InvocationUtils;
import com.dianping.pigeon.remoting.invoker.Client;
import com.dianping.pigeon.remoting.invoker.config.InvokerConfig;
import com.dianping.pigeon.remoting.invoker.domain.InvokerContext;
import com.dianping.pigeon.remoting.invoker.process.InvokerContextProcessor;
import com.dianping.pigeon.util.TimeUtils;
import com.dianping.pigeon.util.VersionUtils;

public class ContextPrepareInvokeFilter extends InvocationInvokeFilter {

    private static final Logger logger = LoggerLoader.getLogger(ContextPrepareInvokeFilter.class);
    private ConcurrentHashMap<String, Boolean> protoVersionMap = new ConcurrentHashMap<String, Boolean>();
    private ConcurrentHashMap<String, Boolean> compactVersionMap = new ConcurrentHashMap<String, Boolean>();
    private static AtomicLong requestSequenceMaker = new AtomicLong();
    private static final String KEY_COMPACT = "pigeon.invoker.request.compact";
    private static final String KEY_TIMEOUT_RESET = "pigeon.timeout.reset";
    private static final InvokerContextProcessor contextProcessor = ExtensionLoader
            .getExtension(InvokerContextProcessor.class);

    public ContextPrepareInvokeFilter() {
        ConfigManagerLoader.getConfigManager().getBooleanValue(KEY_COMPACT, true);
        ConfigManagerLoader.getConfigManager().getBooleanValue(KEY_TIMEOUT_RESET, true);
    }

    @Override
    public InvocationResponse invoke(ServiceInvocationHandler handler, InvokerContext invocationContext)
            throws Throwable {
        invocationContext.getTimeline().add(new TimePoint(TimePhase.C));

        readMonitorContext(invocationContext);

        initRequest(invocationContext);
        transferContextValueToRequest(invocationContext, invocationContext.getRequest());
        try {
            return handler.handle(invocationContext);
        } finally {
            ContextUtils.clearRequestContext();
        }
    }

    private void readMonitorContext(InvokerContext invocationContext) {
        MonitorTransaction transaction = MonitorLoader.getMonitor().getCurrentCallTransaction();

        if (transaction != null) {
            Client client = invocationContext.getClient();
            String targetApp = RegistryManager.getInstance().getReferencedAppFromCache(client.getAddress());

            transaction.readMonitorContext(targetApp);
        }
    }

    // 初始化Request的createTime和timeout，以便统一这两个值
    private void initRequest(InvokerContext invokerContext) {
        InvocationRequest request = invokerContext.getRequest();

        if (!(request instanceof UnifiedRequest)) {
            compactRequest(invokerContext);
        } else {
            UnifiedRequest _request = (UnifiedRequest) request;
            _request.setServiceInterface(invokerContext.getInvokerConfig().getServiceInterface());
            _request.setParameterTypes(invokerContext.getParameterTypes());
        }

        checkSerialize(invokerContext);
        request = invokerContext.getRequest();
        request.setSequence(requestSequenceMaker.incrementAndGet() * -1);
        request.setCreateMillisTime(TimeUtils.currentTimeMillis());
        request.setMessageType(Constants.MESSAGE_TYPE_SERVICE);

        InvokerConfig<?> invokerConfig = invokerContext.getInvokerConfig();
        if (invokerConfig != null) {
            request.setTimeout(invokerConfig.getTimeout(invokerContext.getMethodName()));

            if (ConfigManagerLoader.getConfigManager().getBooleanValue(KEY_TIMEOUT_RESET, true)) {
                Object timeout = ContextUtils.getLocalContext(Constants.REQUEST_TIMEOUT);
                if (timeout != null) {
                    int timeout_ = Integer.parseInt(String.valueOf(timeout));
                    if (timeout_ > 0 && timeout_ < request.getTimeout()) {
                        request.setTimeout(timeout_);
                    }
                }
            }
            if (Constants.CALL_ONEWAY.equalsIgnoreCase(invokerConfig.getCallType())) {
                request.setCallType(Constants.CALLTYPE_NOREPLY);
            } else {
                request.setCallType(Constants.CALLTYPE_REPLY);
            }
        }
    }

    private void checkSerialize(InvokerContext invokerContext) {
        InvocationRequest request = invokerContext.getRequest();

        if (SerializerType.isProto(request.getSerialize())
                || SerializerType.isFst(request.getSerialize())) {
            checkVersion(invokerContext);
        } else if (SerializerType.isThrift(request.getSerialize())) {
            checkProtocol(invokerContext);
        }

    }

    // 缺服务是否支持判断
    private void checkVersion(InvokerContext invokerContext) {
        Client client = invokerContext.getClient();
        InvocationRequest request = invokerContext.getRequest();

        String version = RegistryManager.getInstance().getReferencedVersionFromCache(client.getAddress());

        boolean supported = true;
        if (StringUtils.isBlank(version)) {
            supported = false;
        } else if (protoVersionMap.containsKey(version)) {
            supported = protoVersionMap.get(version);
        } else {
            supported = VersionUtils.isProtoFstSupported(version);
            protoVersionMap.putIfAbsent(version, supported);
        }

        if (!supported) {
            request.setSerialize(SerializerType.HESSIAN.getCode());
            invokerContext.getInvokerConfig().setSerialize(SerializerType.HESSIAN.getName());
        }
    }

    private void checkProtocol(InvokerContext invokerContext) {
        Client client = invokerContext.getClient();
        InvocationRequest request = invokerContext.getRequest();
        boolean supported = false;
        try {
            supported = RegistryManager.getInstance().isSupportNewProtocol(client.getAddress(),
                    request.getServiceName());
        } catch (RegistryException e) {
            supported = false;
        }
        if (!supported) {
            InvocationRequest _request = InvocationUtils.newRequest(invokerContext);
            _request.setSerialize(SerializerType.HESSIAN.getCode());
            invokerContext.setRequest(_request);
        }
    }

    private void compactRequest(InvokerContext invokerContext) {
        boolean isCompact = false;
        if (ConfigManagerLoader.getConfigManager().getBooleanValue(KEY_COMPACT, true)) {
            Client client = invokerContext.getClient();
            String version = RegistryManager.getInstance().getReferencedVersionFromCache(client.getAddress());
            if (StringUtils.isBlank(version)) {
                isCompact = false;
            } else if (compactVersionMap.containsKey(version)) {
                isCompact = compactVersionMap.get(version);
            } else {
                isCompact = VersionUtils.isCompactSupported(version);
                compactVersionMap.putIfAbsent(version, isCompact);
            }
        }
        if (isCompact) {
            invokerContext.setRequest(new CompactRequest(invokerContext));
        }
    }

    private void transferContextValueToRequest(final InvokerContext invocationContext,
                                               final InvocationRequest request) {
        if (request instanceof UnifiedRequest) {
            UnifiedRequest _request = (UnifiedRequest) request;
            _request.setParameterTypes(invocationContext.getParameterTypes());
            transferContextValueToRequest0(_request);
        } else {
            transferContextValueToRequest0(invocationContext, request);
        }
    }

    private void transferContextValueToRequest0(final InvokerContext invocationContext,
                                                final InvocationRequest request) {
        if (contextProcessor != null) {
            contextProcessor.preInvoke(invocationContext);
        }

        if (ContextUtils.getGlobalContext(Constants.CONTEXT_KEY_SOURCE_APP) == null) {
            ContextUtils.putGlobalContext(Constants.CONTEXT_KEY_SOURCE_APP,
                    ConfigManagerLoader.getConfigManager().getAppName());
            ContextUtils.putGlobalContext(Constants.CONTEXT_KEY_SOURCE_IP,
                    ConfigManagerLoader.getConfigManager().getLocalIp());
        }
        request.setGlobalValues(ContextUtils.getGlobalContext());
        ContextUtils.initRequestContext();
        request.setRequestValues(ContextUtils.getRequestContext());
    }

    private void transferContextValueToRequest0(final UnifiedRequest request) {

        if (ContextUtils.getGlobalContext(Constants.CONTEXT_KEY_SOURCE_APP) == null) {
            ContextUtils.putGlobalContext(Constants.CONTEXT_KEY_SOURCE_APP,
                    ConfigManagerLoader.getConfigManager().getAppName());
            ContextUtils.putGlobalContext(Constants.CONTEXT_KEY_SOURCE_IP,
                    ConfigManagerLoader.getConfigManager().getLocalIp());
        }

        Map<String, String> _globalContext = request.getGlobalContext();
        if (_globalContext == null) {
            _globalContext = new HashMap<String, String>();
            request.setGlobalContext(_globalContext);
        }

        Map<String, Serializable> globalContext = ContextUtils.getGlobalContext();

        ContextUtils.convertContext(globalContext, _globalContext);
        Map<String, String> _localContext = request.getLocalContext();
        if (_localContext == null) {
            _localContext = new HashMap<String, String>();
            request.setLocalContext(_localContext);
        }

        Map<String, Serializable> localContext = ContextUtils.getRequestContext();
        ContextUtils.convertContext(localContext, _localContext);
    }

}
