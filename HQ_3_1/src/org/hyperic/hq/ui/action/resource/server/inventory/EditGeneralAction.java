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

package org.hyperic.hq.ui.action.resource.server.inventory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.ejb.ObjectNotFoundException;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.hyperic.hq.appdef.shared.AppdefDuplicateNameException;
import org.hyperic.hq.appdef.shared.ServerValue;
import org.hyperic.hq.authz.shared.PermissionException;
import org.hyperic.hq.bizapp.shared.AppdefBoss;
import org.hyperic.hq.ui.Constants;
import org.hyperic.hq.ui.action.BaseAction;
import org.hyperic.hq.ui.util.ContextUtils;
import org.hyperic.hq.ui.util.RequestUtils;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.struts.action.ActionForm;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;

    /**
    * Edit the GeneralProperties of a server with the attributes specified in the given
    * <code>ServerForm</code>.
    *
    */ 

public class EditGeneralAction extends BaseAction {

    public ActionForward execute(ActionMapping mapping,
                                 ActionForm form,
                                 HttpServletRequest request,
                                 HttpServletResponse response)
        throws Exception {
        Log log = LogFactory.getLog(EditGeneralAction.class.getName());
        ServerForm serverForm = (ServerForm) form;
        Integer rid = serverForm.getRid();
        Integer entityType = serverForm.getType();
        Map forwardParams = new HashMap(2);
        forwardParams.put(Constants.RESOURCE_PARAM, rid);
        forwardParams.put(Constants.RESOURCE_TYPE_ID_PARAM, entityType);
        ActionForward forward = checkSubmit(request, mapping, form,
                                                forwardParams, YES_RETURN_PATH);
        if (forward != null) {
            return forward;
        }         
        try {
            ServletContext ctx = getServlet().getServletContext();
            Integer sessionId = RequestUtils.getSessionId(request);
            AppdefBoss boss = ContextUtils.getAppdefBoss(ctx);
            Integer serverId = RequestUtils.getResourceId(request);
            ServerValue sValue = boss.findServerById(sessionId.intValue(), serverId);
            serverForm.updateServerValue(sValue);
            ServerValue updatedServer =
              boss.updateServer(sessionId.intValue(), sValue, null);
            // XXX: enable when we have a confirmed functioning API
            log.trace("saving server [" + sValue.getName()
                               + "]" + " with attributes " + serverForm);
            RequestUtils.setConfirmation(request,
                     "resource.server.inventory.confirm.EditGeneralProperties",
                     updatedServer.getName());
                                         
            return returnSuccess(request, mapping, Constants.RESOURCE_PARAM,
                                 serverId, YES_RETURN_PATH);
        }
        catch (ObjectNotFoundException oe) {
            RequestUtils
                .setError(request,
                          "resource.server.inventory.error.ServerNotFound",
                          "resourceType");
            return returnFailure(request, mapping);
        }
        catch (AppdefDuplicateNameException e1) {
            RequestUtils
                .setError(request,
                          Constants.ERR_DUP_RESOURCE_FOUND);
            return returnFailure(request, mapping);
        }
    }
}
