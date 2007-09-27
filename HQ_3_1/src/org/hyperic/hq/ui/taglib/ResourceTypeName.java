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

package org.hyperic.hq.ui.taglib;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.jsp.JspException;
import javax.servlet.jsp.JspTagException;
import javax.servlet.jsp.JspWriter;
import javax.servlet.jsp.tagext.TagSupport;

import org.apache.struts.util.RequestUtils;
import org.apache.taglibs.standard.tag.common.core.NullAttributeException;
import org.apache.taglibs.standard.tag.el.core.ExpressionUtil;
import org.hyperic.hq.appdef.shared.AppdefEntityConstants;

/**
 + Maps a resource type id provided and returns the resources display name.
 *
 * The property <CODE>typeId</CODE> is the jstl-el that is evaluated for the type id
 * The optional property <CODE>var</CODE> is the request level property
 *  to set instead of printing to the tag's out.println();
 */
public class ResourceTypeName extends TagSupport {

    //----------------------------------------------------instance variables
    private JspWriter output;
    private HttpServletRequest request;
    
    /** Holds value of property typeId. */
    private String typeId = new String();    
    private int id;
    
    /** Holds value of property var. */
    private String var;
    
    //----------------------------------------------------constructors

    public ResourceTypeName() {
        super();
    }

    //----------------------------------------------------public methods
    public int doStartTag() throws JspException{

        request = (HttpServletRequest) this.pageContext.getRequest();
        output = pageContext.getOut();
        String resourceTypeName = new String();
        
       try {
            id = ((Integer) ExpressionUtil.evalNotNull( "spider", 
                                                        "typeId", 
                                                        getTypeId(), 
                                                        Integer.class, 
                                                        this, 
                                                        pageContext )).intValue();
        }
        catch (NullAttributeException ne) {
            throw new JspTagException( " typeId not found");
        }
        catch (JspException je) {
            throw new JspTagException( je.toString() );
        }
        
        if(AppdefEntityConstants.APPDEF_TYPE_PLATFORM == id)
            resourceTypeName = RequestUtils.message(pageContext, null, null, "resource.type.Platform", null);
        else if(AppdefEntityConstants.APPDEF_TYPE_SERVER == id)
            resourceTypeName = RequestUtils.message(pageContext, null, null, "resource.type.Server", null);
        else if(AppdefEntityConstants.APPDEF_TYPE_SERVICE == id)
            resourceTypeName = RequestUtils.message(pageContext, null, null, "resource.type.Service", null);
        else if(AppdefEntityConstants.APPDEF_TYPE_APPLICATION == id)
            resourceTypeName = RequestUtils.message(pageContext, null, null, "resource.type.Application", null);
        else if(AppdefEntityConstants.APPDEF_TYPE_GROUP == id)
            resourceTypeName = RequestUtils.message(pageContext, null, null, "resource.type.Group", null);
          
        try {
            String tmpVar = this.getVar();
            if (tmpVar == null || "".equals(tmpVar.trim())) {
                output.print( resourceTypeName );
            } else{
                resourceTypeName = resourceTypeName.toLowerCase();
                request.setAttribute(tmpVar, resourceTypeName);
            }
        }
        catch(IOException e){
            throw new JspException(e);        
        }
        
	return SKIP_BODY;
    }   
    
    
    /** Getter for property typeId.
     * @return Value of property typeId.
     *
     */
    public String getTypeId() {
        return this.typeId;
    }
    
    /** Setter for property typeId.
     * @param typeId New value of property typeId.
     *
     */
    public void setTypeId(String typeId) {
        this.typeId = typeId;
    }
    
    /** Getter for property var.
     * @return Value of property var.
     *
     */
    public String getVar() {
        return this.var;
    }
    
    /** Setter for property var.
     * @param var New value of property var.
     *
     */
    public void setVar(String var) {
        this.var = var;
    }
    
}
