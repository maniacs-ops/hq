/*
 * NOTE: This copyright does *not* cover user programs that use HQ
 * program services by normal system calls through the application
 * program interfaces provided as part of the Hyperic Plug-in Development
 * Kit or the Hyperic Client Development Kit - this is merely considered
 * normal use of the program, and does *not* fall under the heading of
 * "derived work".
 *
 * Copyright (C) [2004, 2005, 2006], Hyperic, Inc.
 * This file is part of HQ.
 *
 * HQ is free software; you can redistribute it and/or modify
 * it under the terms version 2 of the GNU General Public License as
 * published by the Free Software Foundation. This program is distributed
 * in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307
 * USA.
 */

package org.hyperic.hq.livedata.server.session;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hyperic.hq.product.LiveDataPluginManager;
import org.hyperic.hq.product.ProductPlugin;
import org.hyperic.hq.product.PluginNotFoundException;
import org.hyperic.hq.product.PluginException;
import org.hyperic.hq.product.server.session.ProductManagerEJBImpl;
import org.hyperic.hq.livedata.agent.client.LiveDataClient;
import org.hyperic.hq.appdef.shared.AgentConnectionUtil;
import org.hyperic.hq.appdef.shared.AppdefEntityID;
import org.hyperic.hq.appdef.shared.AgentNotFoundException;
import org.hyperic.hq.appdef.shared.AppdefEntityNotFoundException;
import org.hyperic.hq.appdef.shared.ConfigManagerLocal;
import org.hyperic.hq.appdef.shared.AppdefEntityValue;
import org.hyperic.hq.appdef.shared.AppdefResourceTypeValue;
import org.hyperic.hq.appdef.shared.ConfigFetchException;
import org.hyperic.hq.appdef.server.session.ConfigManagerEJBImpl;
import org.hyperic.hq.authz.shared.PermissionException;
import org.hyperic.hq.authz.shared.AuthzSubjectValue;
import org.hyperic.hq.common.SystemException;
import org.hyperic.hq.livedata.shared.LiveDataManagerLocal;
import org.hyperic.hq.livedata.shared.LiveDataManagerUtil;
import org.hyperic.hq.livedata.shared.LiveDataException;
import org.hyperic.hq.livedata.shared.LiveDataResult;
import org.hyperic.hq.livedata.shared.LiveDataCommand;
import org.hyperic.hq.agent.client.AgentConnection;
import org.hyperic.util.config.ConfigResponse;
import org.hyperic.util.config.ConfigSchema;
import org.hyperic.util.StringUtil;

import javax.ejb.SessionContext;
import javax.ejb.SessionBean;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;

/**
 * @ejb:bean name="LiveDataManager"
 *      jndi-name="ejb/livedata/LiveDataManager"
 *      local-jndi-name="LocalLiveDataManager"
 *      view-type="local"
 *      type="Stateless"
 */
public class LiveDataManagerEJBImpl implements SessionBean {

    private static Log _log = LogFactory.getLog(LiveDataManagerEJBImpl.class);

    private LiveDataPluginManager _manager;
    private Cache _cache;

    private static final String CACHENAME = "LiveData";
    private static final long NO_CACHE = -1;

    /** @ejb:create-method */
    public void ejbCreate() {

        // Initialize local objects
        try {
            _manager = (LiveDataPluginManager) ProductManagerEJBImpl.
                getOne().getPluginManager(ProductPlugin.TYPE_LIVE_DATA);
            _cache = CacheManager.getInstance().getCache(CACHENAME);
        } catch (Exception e) {
            _log.error("Unable to initialize LiveData manager", e);
        }
    }

    public static LiveDataManagerLocal getOne() {
        try {
            return LiveDataManagerUtil.getLocalHome().create();
        } catch (Exception e) {
            throw new SystemException(e);
        }
    }

    public void ejbPostCreate() {}
    public void ejbActivate() {}
    public void ejbPassivate() {}
    public void ejbRemove() {}
    public void setSessionContext(SessionContext ctx) {}

    /**
     * Live data subsystem uses measurement configs.
     */
    private ConfigResponse getConfig(AuthzSubjectValue subject,
                                     LiveDataCommand command)
        throws LiveDataException
    {
        ConfigManagerLocal cManager = ConfigManagerEJBImpl.getOne();

        try {
            AppdefEntityID id = command.getAppdefEntityID();
            ConfigResponse config = command.getConfig();

            try {
                ConfigResponse mConfig = cManager.
                    getMergedConfigResponse(subject,
                                            ProductPlugin.TYPE_MEASUREMENT,
                                            id, true);
                mConfig.merge(config, false);
                return mConfig;
            } catch (ConfigFetchException e) {
                // No measurement config?  No problem
                return config;
            }
        } catch (Exception e) {
            throw new LiveDataException(e);
        }
    }

    /**
     * Get the appdef type for a given entity id.
     */
    private String getType(AuthzSubjectValue subject, LiveDataCommand cmd)
        throws AppdefEntityNotFoundException, PermissionException
    {
        AppdefEntityID id = cmd.getAppdefEntityID();
        AppdefEntityValue val = new AppdefEntityValue(id, subject);
        AppdefResourceTypeValue typeVal = val.getResourceTypeValue();
        return typeVal.getName();
    }

    private void putElement(LiveDataCommand cmd, LiveDataResult res) {
        putElement(new LiveDataCommand[] { cmd },
                   new LiveDataResult[] { res });
    }

    private void putElement(LiveDataCommand[] cmds, LiveDataResult[] res) {
        LiveDataCacheKey key = new LiveDataCacheKey(cmds);
        LiveDataCacheObject obj = new LiveDataCacheObject(res);
        Element e = new Element(key, obj);
        _cache.put(e);
    }

    private LiveDataResult getElement(LiveDataCommand cmd, long timeout) {
        LiveDataResult[] res = getElement(new LiveDataCommand[] { cmd },
                                          timeout);
        return res == null ? null : res[0];
    }

    private LiveDataResult[] getElement(LiveDataCommand[] cmds, long timeout) {
        LiveDataCacheKey key = new LiveDataCacheKey(cmds);
        Element e = _cache.get(key);
        if (e == null) {
            return null;
        }

        LiveDataCacheObject obj = (LiveDataCacheObject)e.getObjectValue();

        if (System.currentTimeMillis() > obj.getCtime() + timeout) {
            // Object is expired
            _cache.remove(key);
            return null;
        }

        _log.info("Returning cached result " +
                  StringUtil.arrayToString(obj.getResult()));
        return obj.getResult();
    }

    /**
     * Run the given live data command.
     *
     * @ejb:interface-method
     */
    public LiveDataResult getData(AuthzSubjectValue subject,
                                  LiveDataCommand cmd)
        throws AppdefEntityNotFoundException, PermissionException,
               AgentNotFoundException, LiveDataException
    {
        return getData(subject, cmd, NO_CACHE);
    }

    /**
     * Run the given live data command.  If cached data is found that is not
     * older than the cachedTimeout the cached data will be returned.
     *
     * @param cacheTimeout
     * @ejb:interface-method
     */
    public LiveDataResult getData(AuthzSubjectValue subject,
                                  LiveDataCommand cmd, long cacheTimeout)
        throws PermissionException, AgentNotFoundException,
               AppdefEntityNotFoundException, LiveDataException
    {
        // Attempt load from cache
        LiveDataResult res;

        if (cacheTimeout != NO_CACHE) {
            res = getElement(cmd, cacheTimeout);
            if (res != null) {
                return res;
            }
        }

        AppdefEntityID id = cmd.getAppdefEntityID();
        AgentConnection conn = AgentConnectionUtil.getClient(id);
        LiveDataClient client = new LiveDataClient(conn);

        ConfigResponse config = getConfig(subject, cmd);
        String type = getType(subject, cmd);

        res = client.getData(id, type, cmd.getCommand(), config);

        if (cacheTimeout != NO_CACHE) {
            putElement(cmd, res);
        }

        return res;
    }

    /**
     * Run a list of live data commands in batch.
     *
     * @ejb:interface-method
     */
    public LiveDataResult[] getData(AuthzSubjectValue subject,
                                    LiveDataCommand[] commands)
        throws AppdefEntityNotFoundException, PermissionException, 
               AgentNotFoundException, LiveDataException
    {
       return getData(subject, commands, NO_CACHE);
    }

    /**
     * Run a list of live data commands in batch.  If cached data is found
     * that is not older than the cacheTimeout the cached data will be returned.
     *
     * @param cacheTimeout The cache timeout given in milliseconds.
     * @ejb:interface-method
     */
    public LiveDataResult[] getData(AuthzSubjectValue subject,
                                    LiveDataCommand[] commands,
                                    long cacheTimeout)
        throws PermissionException, AppdefEntityNotFoundException,
               AgentNotFoundException, LiveDataException
    {
        // Attempt load from cache
        LiveDataResult[] res;
        if (cacheTimeout != NO_CACHE) {
            res = getElement(commands, cacheTimeout);
            if (res != null) {
                return res;
            }
        }

        HashMap buckets = new HashMap();
        for (int i = 0; i < commands.length; i++) {
            LiveDataCommand cmd = commands[i];
            AppdefEntityID id = cmd.getAppdefEntityID();
            AgentConnection conn = AgentConnectionUtil.getClient(id);

            ConfigResponse config = getConfig(subject, cmd);
            String type = getType(subject, cmd);

            LiveDataExecutorCommand exec =
                new LiveDataExecutorCommand(id, type, cmd.getCommand(), config);

            List queue = (List)buckets.get(conn);
            if (queue == null) {
                queue = new ArrayList();
                queue.add(exec);
                buckets.put(conn, queue);
            } else {
                queue.add(exec);
            }
        }

        LiveDataExecutor executor = new LiveDataExecutor();
        for (Iterator i = buckets.keySet().iterator(); i.hasNext(); ) {
            AgentConnection conn = (AgentConnection)i.next();
            List cmds = (List)buckets.get(conn);
            executor.getData(new LiveDataClient(conn), cmds);
        }

        executor.shutdown();

        res = executor.getResult();

        if (cacheTimeout != NO_CACHE) {
            putElement(commands, res);
        }

        return res;
    }

    /**
     * Get the available commands for a given resources.
     *
     * @ejb:interface-method 
     */
    public String[] getCommands(AuthzSubjectValue subject, AppdefEntityID id)
        throws PluginException, PermissionException
    {
        try {
            AppdefEntityValue val = new AppdefEntityValue(id, subject);
            AppdefResourceTypeValue tVal = val.getResourceTypeValue();

            return _manager.getCommands(tVal.getName());
        } catch (AppdefEntityNotFoundException e) {
            throw new PluginNotFoundException("No plugin found for " + id, e);
        }
    }

    /**
     * Get the ConfigSchema for a given resource.
     * 
     * @ejb:interface-method
     */
    public ConfigSchema getConfigSchema(AuthzSubjectValue subject,
                                        AppdefEntityID id, String command)
        throws PluginException, PermissionException
    {
        try {
            AppdefEntityValue val = new AppdefEntityValue(id, subject);
            AppdefResourceTypeValue tVal = val.getResourceTypeValue();

            return _manager.getConfigSchema(tVal.getName(), command);
        } catch (AppdefEntityNotFoundException e) {
            throw new PluginNotFoundException("No plugin found for " + id, e);
        }
    }
}
