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

package org.hyperic.hq.ui.action.resource.platform.inventory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.struts.action.ActionForm;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;
import org.hyperic.hq.appdef.shared.AgentValue;
import org.hyperic.hq.appdef.shared.AppdefDuplicateFQDNException;
import org.hyperic.hq.appdef.shared.AppdefEntityID;
import org.hyperic.hq.appdef.shared.IpValue;
import org.hyperic.hq.appdef.shared.PlatformValue;
import org.hyperic.hq.bizapp.shared.AppdefBoss;
import org.hyperic.hq.common.ApplicationException;
import org.hyperic.hq.ui.Constants;
import org.hyperic.hq.ui.action.BaseAction;
import org.hyperic.hq.ui.action.resource.platform.PlatformForm;
import org.hyperic.hq.ui.util.BizappUtils;
import org.hyperic.hq.ui.util.ContextUtils;
import org.hyperic.hq.ui.util.RequestUtils;

/**
 * A <code>BaseAction</code> subclass that edits the type and
 * network properties of a platform in the BizApp.
 */
public class EditPlatformTypeNetworkPropertiesAction extends BaseAction {

    // ---------------------------------------------------- Public Methods

    /**
     * Edit the platform with the attributes specified in the given
     * <code>PlatformForm</code>.
     */
    public ActionForward execute(ActionMapping mapping,
                                 ActionForm form,
                                 HttpServletRequest request,
                                 HttpServletResponse response)
        throws Exception {
         Log log = LogFactory
             .getLog(EditPlatformTypeNetworkPropertiesAction.class.getName());

        PlatformForm editForm = (PlatformForm) form;
        AppdefEntityID aeid = new AppdefEntityID(editForm.getType().intValue(),
                                                 editForm.getRid());

        HashMap forwardParams = new HashMap(2);
        forwardParams.put(Constants.ENTITY_ID_PARAM, aeid.getAppdefKey());
        forwardParams.put(Constants.ACCORDION_PARAM, "1");

        try {
            ActionForward forward = checkSubmit(request, mapping, form,
                                                forwardParams);
            if (forward != null) {
                return forward;
            }

            ServletContext ctx = getServlet().getServletContext();
            Integer sessionId = RequestUtils.getSessionId(request);
            AppdefBoss boss = ContextUtils.getAppdefBoss(ctx);

            // first make sure the form's "machine type" represents a
            // valid platform type
            
            // XXX The code below is commented since we should not be 
            // editing the platform's machine type once the platform is created
            
            /*
            Integer platformTypeId = editForm.getResourceType();
            log.trace("finding platform type [" + platformTypeId + "]");
            PlatformTypeValue platformType =
                boss.findPlatformTypeById(sessionId.intValue(),
                                          platformTypeId); */

            // now set up the platform
            PlatformValue platform =
                boss.findPlatformById(sessionId.intValue(), aeid.getId());
            
            if (platform == null) {
                RequestUtils
                    .setError(request,
                              "resource.platform.error.PlatformNotFound");
                return returnFailure(request, mapping, forwardParams);
            }

            editForm.updatePlatformValue(platform);

            AgentValue agent =
                BizappUtils.getAgentConnection(sessionId.intValue(), boss,
                                               request, editForm);
            if (agent != null) {
                platform.setAgent(agent);
            }

            log.trace("editing general properties of platform [" +
                      platform.getName() + "]" + " with attributes " +
                      platform + " and ips " +
                      Arrays.asList(platform.getIpValues())); 
            boss.updatePlatform(sessionId.intValue(), platform);

            /* XXX - Not sure why it's the job of the UI to start an auto scan
            BizappUtils.startAutoScan(ctx,
                                      sessionId.intValue(),
                                      newPlatform.getEntityId());
             */
            
            RequestUtils
                .setConfirmation(request,
                                 "resource.platform.inventory.confirm.EditTypeNetworkProperties",
                                 platform.getName());
            return returnSuccess(request, mapping, forwardParams);
        }        
        catch (AppdefDuplicateFQDNException e) {
            RequestUtils
                .setError(request,
                          "resource.platform.inventory.error.DuplicateFQDN",
                          "platformType");
            return returnFailure(request, mapping, forwardParams);

        }
        catch (ApplicationException e) {
            RequestUtils
                .setErrorObject(request,"dash.autoDiscovery.import.Error",
                                e.getMessage());
            return returnFailure(request, mapping);
        }        
    }
}
