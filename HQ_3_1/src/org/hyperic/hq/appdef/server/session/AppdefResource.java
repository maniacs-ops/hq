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

package org.hyperic.hq.appdef.server.session;

import org.hyperic.hq.appdef.AppdefBean;
import org.hyperic.hq.appdef.shared.AppdefEntityID;

/**
 * abstract base class for all appdef resources
 */
public abstract class AppdefResource extends AppdefBean
{
    protected String name;
    protected String sortName;
    protected String description;
    protected String modifiedBy;
    protected String owner;
    protected String location;

    /**
     * default constructor
     */
    public AppdefResource()
    {
        super();
    }

    public String getDescription()
    {
        return this.description;
    }

    public void setDescription(String description)
    {
        this.description = description;
    }

    public String getModifiedBy()
    {
        return this.modifiedBy;
    }

    public void setModifiedBy(String modifiedBy)
    {
        this.modifiedBy = modifiedBy;
    }

    public String getOwner()
    {
        return this.owner;
    }

    public void setOwner(String owner)
    {
        this.owner = owner;
    }

    public String getLocation()
    {
        return this.location;
    }

    public void setLocation(String location)
    {
        this.location = location;
    }

    // Property accessors
    public String getName()
    {
        return this.name;
    }

    public void setName(String name)
    {
        this.name = name;
        setSortName(name);
    }

    public String getSortName()
    {
        return this.sortName;
    }

    public void setSortName(String sortName)
    {
        if (sortName != null) {
            this.sortName = sortName.toUpperCase();
        } else {
            this.sortName = null;
        }
    }

    /**
     * Get the appdefEntityId for this platform
     * legacy code from EJB entity bean
     */
    public abstract AppdefEntityID getEntityId();
    
    public abstract AppdefResourceType getAppdefResourceType();
}
