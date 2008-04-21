/*
 * NOTE: This copyright does *not* cover user programs that use HQ
 * program services by normal system calls through the application
 * program interfaces provided as part of the Hyperic Plug-in Development
 * Kit or the Hyperic Client Development Kit - this is merely considered
 * normal use of the program, and does *not* fall under the heading of
 * "derived work".
 *
 * Copyright (C) [2004-2008], Hyperic, Inc.
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

package org.hyperic.hq.control.agent.client;

import org.hyperic.hq.appdef.Agent;
import org.hyperic.hq.appdef.server.session.AgentManagerEJBImpl;
import org.hyperic.hq.appdef.shared.AgentNotFoundException;
import org.hyperic.hq.appdef.shared.AppdefEntityID;
import org.hyperic.hq.application.HQApp;
import org.hyperic.hq.bizapp.agent.client.SecureAgentConnection;
import org.hyperic.hq.transport.AgentProxyFactory;

/**
 * A factory for returning Control Commands clients depending on if the agent 
 * uses the legacy or new transport.
 */
public class ControlCommandsClientFactory {

    private static final ControlCommandsClientFactory INSTANCE = 
            new ControlCommandsClientFactory();

    private ControlCommandsClientFactory() {
    }

    public static ControlCommandsClientFactory getInstance() {
        return INSTANCE;
    }

    public ControlCommandsClient getClient(AppdefEntityID aid) 
        throws AgentNotFoundException {
        
        Agent agent = AgentManagerEJBImpl.getOne().getAgent(aid);

        return getClient(agent);
    }

    public ControlCommandsClient getClient(String agentToken) 
        throws AgentNotFoundException {
        
        Agent agent = AgentManagerEJBImpl.getOne().getAgent(agentToken);

        return getClient(agent);
    }
    
    private ControlCommandsClient getClient(Agent agent) {
        if (agent.isNewTransportAgent()) {
            AgentProxyFactory factory = HQApp.getInstance().getAgentProxyFactory();
            
            return new ControlCommandsClientImpl(agent, factory);
        } else {
            return new LegacyControlCommandsClientImpl(new SecureAgentConnection(agent));            
        }         
    }

}
