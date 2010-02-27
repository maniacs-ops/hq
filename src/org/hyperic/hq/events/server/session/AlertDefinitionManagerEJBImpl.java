/*
 * NOTE: This copyright does *not* cover user programs that use HQ
 * program services by normal system calls through the application
 * program interfaces provided as part of the Hyperic Plug-in Development
 * Kit or the Hyperic Client Development Kit - this is merely considered
 * normal use of the program, and does *not* fall under the heading of
 * "derived work".
 *
 * Copyright (C) [2004-2009], Hyperic, Inc.
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

package org.hyperic.hq.events.server.session;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

import javax.ejb.CreateException;
import javax.ejb.FinderException;
import javax.ejb.RemoveException;
import javax.ejb.SessionBean;
import javax.ejb.SessionContext;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hyperic.dao.DAOFactory;
import org.hyperic.hibernate.PageInfo;
import org.hyperic.hq.appdef.shared.AppdefEntityID;
import org.hyperic.hq.appdef.shared.AppdefEntityTypeID;
import org.hyperic.hq.application.HQApp;
import org.hyperic.hq.authz.server.session.AuthzSubject;
import org.hyperic.hq.authz.server.session.Resource;
import org.hyperic.hq.authz.server.session.ResourceManagerEJBImpl;
import org.hyperic.hq.authz.shared.PermissionException;
import org.hyperic.hq.authz.shared.PermissionManagerFactory;
import org.hyperic.hq.authz.shared.ResourceOperationsHelper;
import org.hyperic.hq.bizapp.shared.action.EnableAlertDefActionConfig;
import org.hyperic.hq.common.SystemException;
import org.hyperic.hq.escalation.server.session.Escalation;
import org.hyperic.hq.escalation.server.session.EscalationManagerEJBImpl;
import org.hyperic.hq.escalation.shared.EscalationManagerLocal;
import org.hyperic.hq.events.ActionCreateException;
import org.hyperic.hq.events.AlertConditionCreateException;
import org.hyperic.hq.events.AlertDefinitionCreateException;
import org.hyperic.hq.events.AlertSeverity;
import org.hyperic.hq.events.EventConstants;
import org.hyperic.hq.events.shared.ActionValue;
import org.hyperic.hq.events.shared.AlertConditionValue;
import org.hyperic.hq.events.shared.AlertDefinitionManagerLocal;
import org.hyperic.hq.events.shared.AlertDefinitionManagerUtil;
import org.hyperic.hq.events.shared.AlertDefinitionValue;
import org.hyperic.hq.events.shared.RegisteredTriggerManagerLocal;
import org.hyperic.hq.events.shared.RegisteredTriggerValue;
import org.hyperic.hq.measurement.server.session.Measurement;
import org.hyperic.hq.measurement.server.session.MeasurementDAO;
import org.hyperic.util.config.ConfigResponse;
import org.hyperic.util.config.EncodingException;
import org.hyperic.util.config.InvalidOptionException;
import org.hyperic.util.config.InvalidOptionValueException;
import org.hyperic.util.pager.PageControl;
import org.hyperic.util.pager.PageList;
import org.hyperic.util.pager.Pager;
import org.hyperic.util.pager.SortAttribute;
import org.hyperic.util.timer.StopWatch;

/**
 * <p> Stores Events to and deletes Events from storage
 *
 * </p>
 * @ejb:bean name="AlertDefinitionManager"
 *      jndi-name="ejb/events/AlertDefinitionManager"
 *      local-jndi-name="LocalAlertDefinitionManager"
 *      view-type="local"
 *      type="Stateless"
 * @ejb:transaction type="Required"
 *
 */
public class AlertDefinitionManagerEJBImpl
    extends SessionBase
    implements SessionBean
{
    private Log log = LogFactory.getLog(AlertDefinitionManagerEJBImpl.class);

    private RegisteredTriggerManagerLocal registeredTriggerManager = RegisteredTriggerManagerEJBImpl.getOne();
    
    private final String VALUE_PROCESSOR =
        PagerProcessor_events.class.getName();

    private Pager _valuePager;

    private AlertDefinitionDAO getAlertDefDAO() {
        return new AlertDefinitionDAO(DAOFactory.getDAOFactory());
    }

    private ActionDAO getActionDAO() {
        return new ActionDAO(DAOFactory.getDAOFactory());
    }

    private AlertDAO getAlertDAO() {
        return new AlertDAO(DAOFactory.getDAOFactory());
    }

    private AlertConditionDAO getConditionDAO() {
        return new AlertConditionDAO(DAOFactory.getDAOFactory());
    }

    private boolean deleteAlertDefinitionStuff(AuthzSubject subj,
                                               AlertDefinition alertdef,
                                               EscalationManagerLocal escMan) {
        StopWatch watch = new StopWatch();

        // Delete escalation state
        watch.markTimeBegin("endEscalation");
        if (alertdef.getEscalation() != null &&
            !alertdef.isResourceTypeDefinition()) {
            escMan.endEscalation(alertdef);
        }
        watch.markTimeEnd("endEscalation");

        EventsStartupListener.getAlertDefinitionChangeCallback()
            .postDelete(alertdef);

        if (log.isDebugEnabled()) {
            log.debug("deleteAlertDefinitionStuff: " + watch);
        }

        return true;
    }

    /**
     * Remove alert definitions. It is assumed that the subject has permission
     * to remove this alert definition and any of its' child alert definitions.
     */
    private boolean deleteAlertDefinition(AuthzSubject subj,
                                          AlertDefinition alertdef,
                                          boolean force)
        throws RemoveException, PermissionException {
        final boolean debug = log.isDebugEnabled();
        StopWatch watch = new StopWatch();

        EscalationManagerLocal escMan = EscalationManagerEJBImpl.getOne();

        if (force) { // Used when resources are being deleted
            // Disassociate from Resource so that the Resource can be deleted
            alertdef.setResource(null);
        } else {
            // If there are any children, delete them, too
            if (debug) watch.markTimeBegin("delete children");
            List childBag = new ArrayList(alertdef.getChildrenBag());
            for (int i = 0; i < childBag.size(); i++) {
                AlertDefinition child = (AlertDefinition) childBag.get(i);
                deleteAlertDefinitionStuff(subj, child, escMan);
                registeredTriggerManager.deleteTriggers(child);
            }
            if (debug) watch.markTimeBegin("deleteByAlertDefinition");
            getAlertDefDAO().deleteByAlertDefinition(alertdef);
            if (debug) {
                watch.markTimeEnd("deleteByAlertDefinition");
                watch.markTimeEnd("delete children");
            }
        }

        deleteAlertDefinitionStuff(subj, alertdef, escMan);

        if (debug)  watch.markTimeBegin("deleteTriggers");
        registeredTriggerManager.deleteTriggers(alertdef);
        if (debug) watch.markTimeBegin("deleteTriggers");

        if (debug) watch.markTimeBegin("markActionsDeleted");
        getActionDAO().deleteAlertDefinition(alertdef);
        if (debug) watch.markTimeBegin("markActionsDeleted");

        if (debug) watch.markTimeBegin("mark deleted");
        // Disassociated from escalations
        alertdef.setEscalation(null);
        alertdef.setDeleted(true);
        alertdef.setActiveStatus(false);
        // Disassociate from parent
        // This must be at the very end since we use the parent to determine
        // whether or not this is a resource type alert definition.
        alertdef.setParent(null);

        if (debug) {
            watch.markTimeEnd("mark deleted");
            log.debug("deleteAlertDefinition: " + watch);
        }

        return true;
    }

    /**
     * Create a new alert definition
     * @ejb:interface-method
     */
    public AlertDefinitionValue createAlertDefinition(AuthzSubject subj,
                                                      AlertDefinitionValue a)
        throws AlertDefinitionCreateException,
               PermissionException
    {
        if (EventConstants.TYPE_ALERT_DEF_ID.equals(a.getParentId())) {
            // ...check that user has access to resource type alert definitions alert definition's resource...
            canCreateResourceTypeAlertDefinitionTemplate(subj);
        // Subject permissions should have already been checked when creating
        // the parent (resource type) alert definition.
        } else if (!a.parentIdHasBeenSet()) {
            // ...check that user has create permission on alert definition's resource...
            canCreateAlertDefinition(subj, new AppdefEntityID(a.getAppdefType(), 
                                                              a.getAppdefId()));
        }
        
		return createAlertDefinition(a);
	}
	
	 /**
     * Create a new alert definition
     * @ejb:interface-method
     */
    public AlertDefinitionValue createAlertDefinition(AlertDefinitionValue a) {

        // HHQ-1054: since the alert definition mtime is managed explicitly,
        // let's initialize it
        a.initializeMTimeToNow();

        AlertDefinition res = new AlertDefinition();
        ActionDAO aDAO = getActionDAO();
        AlertDefinitionDAO adDAO = getAlertDefDAO();

        // The following is duplicated out of what the EJBImpl did.  Makes sense
        a.cleanAction();
        a.cleanCondition();
        a.cleanTrigger();
        adDAO.setAlertDefinitionValue(res, a);

        // Create new conditions
        AlertConditionValue[] conds = a.getConditions();
        AlertConditionDAO acDAO = getConditionDAO();
        for (int i = 0; i < conds.length; i++) {
            RegisteredTrigger trigger = conds[i].getTriggerId() != null ?
                registeredTriggerManager.findById(conds[i].getTriggerId()) : null;

            AlertCondition cond = res.createCondition(conds[i], trigger);

            if (res.getName() == null || res.getName().length() == 0) {
                Measurement dm = null;
                if (cond.getType() == EventConstants.TYPE_THRESHOLD ||
                    cond.getType() == EventConstants.TYPE_BASELINE) {
                    MeasurementDAO dmDao =
                        new MeasurementDAO(DAOFactory.getDAOFactory());
                    dm = dmDao.findById(new Integer(cond.getMeasurementId()));
                }
                if (dm == null) {
                    log.warn("AlertCondition (id=" + cond.getId() + ") has an " +
                        "associated Measurement (id=" + cond.getMeasurementId() +
                        ") that does not exist, ignoring");
                    continue;
                }
                res.setName(describeCondition(cond, dm));
            }

            if (cond.getType() == EventConstants.TYPE_ALERT) {
                setEnableAlertDefAction(a, cond.getMeasurementId());
            }

            acDAO.save(cond);
        }

        // Create actions
        ActionValue[] actions = a.getActions();
        ActionDAO actDAO = DAOFactory.getDAOFactory().getActionDAO();
        for (int i = 0; i < actions.length; i++) {
            Action parent = null;

            if (actions[i].getParentId() != null)
                parent = aDAO.findById(actions[i].getParentId());

            Action act = res.createAction(actions[i].getClassname(),
                                          actions[i].getConfig(), parent);
            actDAO.save(act);
        }

        // Set triggers
        RegisteredTriggerValue[] triggers = a.getTriggers();
        if (triggers.length != 0) {


            for (int i = 0; i < triggers.length; i++) {
                RegisteredTrigger trig;

                // Triggers were already created by bizapp, so we only need
                // to add them to our list
                trig = registeredTriggerManager.findById(triggers[i].getId());
                trig.setAlertDefinition(res);
            }
        }

        Integer esclId = a.getEscalationId();
        if (esclId != null) {
            Escalation escalation =
                EscalationManagerEJBImpl.getOne().findById(esclId);
            res.setEscalation(escalation);
        }
        // Alert definitions are the root of the cascade relationship, so
        // we must explicitly save them
        adDAO.save(res);

        EventsStartupListener.getAlertDefinitionChangeCallback()
            .postCreate(res);

        return res.getAlertDefinitionValue();
    }

    /**
     * Update just the basics
     * @throws PermissionException
     *
     * @ejb:interface-method
     */
    public void updateAlertDefinitionBasic(AuthzSubject subj, Integer id,
                                           String name, String desc,
                                           int priority, boolean activate)
    throws PermissionException {
        final boolean debug = log.isDebugEnabled();

        StopWatch watch = new StopWatch();
        AlertDefinition def = getAlertDefDAO().findById(id);

        // ...check that user has modify permission on alert definition's resource...
        canModifyAlertDefinition(subj, def.getAppdefEntityId());

        int initCapacity = def.getChildren().size() + 1;
        List alertdefs = new ArrayList(initCapacity);
        List defIds = new ArrayList(initCapacity);
        
        alertdefs.add(def);

        if (debug) watch.markTimeBegin("getChildren");
        
        // If there are any children, add them, too
        alertdefs.addAll(def.getChildren());

        if (debug) {
            watch.markTimeEnd("getChildren");
            watch.markTimeBegin("updateBasic");
        }

        for (Iterator it = alertdefs.iterator(); it.hasNext(); ) {
            def = (AlertDefinition) it.next();

            def.setName(name);
            def.setDescription(desc);
            def.setPriority(priority);

            if (def.isActive() != activate || def.isEnabled() != activate) {
                def.setActiveStatus(activate);
                AlertAudit.enableAlert(def, subj);
                defIds.add(def.getId());
            }
            
            def.setMtime(System.currentTimeMillis());

            EventsStartupListener.getAlertDefinitionChangeCallback().postUpdate(def);
        }
        
        if (debug) {
            watch.markTimeEnd("updateBasic");
            watch.markTimeBegin("setAlertDefinitionTriggersEnabled");
        }
        
        registeredTriggerManager.setAlertDefinitionTriggersEnabled(defIds, activate);
        
        if (debug) {
            watch.markTimeEnd("setAlertDefinitionTriggersEnabled");
            log.debug("updateAlertDefinitionBasic[" + initCapacity + "]: " + watch);
        }
    }

    /**
     * Get the EnableAlertDefAction ActionValue from an
     * AlertDefinitionValue.  If none exists, return null.
     */
    private ActionValue getEnableAlertDefAction(AlertDefinitionValue adv) {
        ActionValue[] actions = adv.getActions();
        EnableAlertDefActionConfig cfg = new EnableAlertDefActionConfig();
        for (int i = 0; i < actions.length; ++i) {
            String actionClass = actions[i].getClassname();
            if (cfg.getImplementor().equals(actionClass))
                return actions[i];
        }
        return null;
    }

    private void setEnableAlertDefAction(AlertDefinitionValue adval, int recoverId) {
        EnableAlertDefActionConfig action =
            new EnableAlertDefActionConfig();

        // Find recovery actions first
        ActionValue recoverAction = getEnableAlertDefAction(adval);
        
        if (recoverAction != null) {
            try {
                ConfigResponse configResponse =
                    ConfigResponse.decode(recoverAction.getConfig());
                action.init(configResponse);

                if (action.getAlertDefId() != recoverId) {
                    action.setAlertDefId(recoverId);
                    recoverAction.setConfig(action
                                                .getConfigResponse()
                                                .encode());
                    adval.updateAction(recoverAction);
                }
            } catch (Exception e) {
                adval.removeAction(recoverAction);
                recoverAction = null;
            }
        }

        // Add action if doesn't exist
        if (recoverAction == null) {
            recoverAction = new ActionValue();
            action.setAlertDefId(recoverId);
            recoverAction.setClassname(action.getImplementor());

            try {
                recoverAction
                .setConfig(action.getConfigResponse().encode());
            } catch (EncodingException e) {
                log.debug("Error encoding EnableAlertDefAction", e);
            } catch (InvalidOptionException e) {
                log.debug("Error encoding EnableAlertDefAction", e);
            } catch (InvalidOptionValueException e) {
                log.debug("Error encoding EnableAlertDefAction", e);
            }

            adval.addAction(recoverAction);
        }
    }
    
    /**
     * Update an alert definition
     * @ejb:interface-method
     */
    public AlertDefinitionValue updateAlertDefinition(AlertDefinitionValue adval)
        throws AlertConditionCreateException, ActionCreateException,
               RemoveException
    {
        AlertDefinitionDAO dao = getAlertDefDAO();
        ActionDAO actDao = getActionDAO();
        AlertDefinition aldef = dao.findById(adval.getId());
        int recoverId = -1;
                
        // See if the conditions changed
        if (adval.getAddedConditions().size()   > 0 ||
            adval.getUpdatedConditions().size() > 0 ||
            adval.getRemovedConditions().size() > 0 )
        {
            // We need to keep old conditions around for the logs.  So
            // we'll create new conditions and update the alert
            // definition, but we won't remove the old conditions.
            AlertConditionValue[] conds = adval.getConditions();
            aldef.clearConditions();
            for (int i = 0; i < conds.length; i++) {
                RegisteredTrigger trigger = null;

                // Trigger ID is null for resource type alerts
                if (conds[i].getTriggerId() != null)
                    trigger = registeredTriggerManager.findById(conds[i].getTriggerId());

                if (conds[i].getType() == EventConstants.TYPE_ALERT) {                    
                    recoverId = conds[i].getMeasurementId();
                }

                aldef.createCondition(conds[i], trigger);
            }
        }
        
        if (recoverId > 0) {
            setEnableAlertDefAction(adval, recoverId);
        } else {
            // Remove recover action if exists
            ActionValue recoverAction = getEnableAlertDefAction(adval);

            if (recoverAction != null) {
                adval.removeAction(recoverAction);
            }
        }

        // See if the actions changed
        if (adval.getAddedActions().size()   > 0 ||
            adval.getUpdatedActions().size() > 0 ||
            adval.getRemovedActions().size() > 0 ||
            adval.getActions().length != aldef.getActions().size())
        {
            // We need to keep old actions around for the logs.  So
            // we'll create new actions and update the alert
            // definition, but we won't remove the old actions.
            ActionValue[] actions = adval.getActions();
            aldef.clearActions();
            for (int i = 0; i <  actions.length; i++) {
                Action parent = null;

                if (actions[i].getParentId() != null)
                    parent = getActionDAO().findById(actions[i].getParentId());

                actDao.save(aldef.createAction(actions[i].getClassname(),
                                               actions[i].getConfig(), parent));
            }
        }

        // Find out the last trigger ID (bizapp should have created them)
        RegisteredTriggerValue[] triggers = adval.getTriggers();
        if (triggers.length > 0) {
            for (int i = 0; i < triggers.length; i++) {
                RegisteredTrigger t = registeredTriggerManager.findById(triggers[i].getId());
                t.setAlertDefinition(aldef);
            }
        }

        // Lastly, the modification time
        adval.setMtime(System.currentTimeMillis());

        // Now set the alertdef
        dao.setAlertDefinitionValueNoRels(aldef, adval);
        if (adval.isEscalationIdHasBeenSet()) {
            Integer esclId = adval.getEscalationId();
            Escalation escl =
                EscalationManagerEJBImpl.getOne().findById(esclId);

            aldef.setEscalation(escl);
        }

        // Alert definitions are the root of the cascade relationship, so
        // we must explicitly save them
        dao.save(aldef);

        EventsStartupListener.getAlertDefinitionChangeCallback()
            .postUpdate(aldef);

        return aldef.getAlertDefinitionValue();
    }

    /**
     * Activate/deactivate an alert definitions.
     *
     * @ejb:interface-method
     */
    public void updateAlertDefinitionsActiveStatus(AuthzSubject subj,
                                                   Integer[] ids,
                                                   boolean activate)
        throws PermissionException
    {
        List alertdefs = new ArrayList();
        AlertDefinitionDAO dao = getAlertDefDAO();
        for (int i = 0; i < ids.length; i++) {
            alertdefs.add((dao.get(ids[i])));
        }

        for (Iterator i = alertdefs.iterator(); i.hasNext(); ) {
            updateAlertDefinitionActiveStatus(subj, (AlertDefinition) i.next(),
                                              activate);
        }
    }

    /**
     * Activate/deactivate an alert definition.
     *@ejb:interface-method
     */
    public void updateAlertDefinitionActiveStatus(AuthzSubject subj,
                                                  AlertDefinition def,
                                                  boolean activate)
        throws PermissionException {

        final boolean debug = log.isDebugEnabled();
        StopWatch watch = new StopWatch();
        
        // ...check that user has modify permission on alert definition's resource...
        canModifyAlertDefinition(subj, def.getAppdefEntityId());

        if (def.isActive() != activate || def.isEnabled() != activate) {
            def.setActiveStatus(activate);
            def.setMtime(System.currentTimeMillis());
            AlertAudit.enableAlert(def, subj);
            
            // process the children
            if (debug) watch.markTimeBegin("getChildren");
            Collection children = def.getChildren();
            if (debug) watch.markTimeEnd("getChildren");
            
            List defIds = new ArrayList(children.size()+1);
            defIds.add(def.getId());

            for (Iterator it=children.iterator(); it.hasNext(); ) {
                AlertDefinition childDef = (AlertDefinition) it.next();
                defIds.add(childDef.getId());
            }

            if (debug) watch.markTimeBegin("setAlertDefinitionTriggersEnabled");
            registeredTriggerManager.setAlertDefinitionTriggersEnabled(defIds, activate);
            if (debug) watch.markTimeEnd("setAlertDefinitionTriggersEnabled");
        }

        if (debug) watch.markTimeBegin("setChildrenActive");
        getAlertDefDAO().setChildrenActive(def, activate);
        if (debug) watch.markTimeEnd("setChildrenActive");

        EventsStartupListener.getAlertDefinitionChangeCallback()
            .postUpdate(def);
        
        if (debug) {
            log.debug("updateAlertDefinitionActiveStatus: " + watch);
        }
    }

    /**
     * Enable/Disable an alert definition. For internal use only where the mtime
     * does not need to be reset on each update.
     *
     * @return <code>true</code> if the enable/disable succeeded.
     * @ejb:interface-method
     */
    public boolean updateAlertDefinitionInternalEnable(AuthzSubject subj,
                                                       AlertDefinition def,
                                                       boolean enable)
        throws PermissionException {

        boolean succeeded = false;

        if (def.isEnabled() != enable) {
            // ...check that user has modify permission on alert definition's resource...
            canModifyAlertDefinition(subj, def.getAppdefEntityId());

            def.setEnabledStatus(enable);
            registeredTriggerManager.setAlertDefinitionTriggersEnabled(def.getId(), enable);
            succeeded = true;
        }

        return succeeded;
    }

    /**
     * Enable/Disable an alert definition. For internal use only where the mtime
     * does not need to be reset on each update.
     *
     * @return <code>true</code> if the enable/disable succeeded.
     * @ejb:interface-method
     */
    public boolean updateAlertDefinitionInternalEnable(AuthzSubject subj,
                                                       Integer defId,
                                                       boolean enable)
        throws FinderException, PermissionException {

        return updateAlertDefinitionInternalEnable(
                    subj, Collections.singletonList(defId), enable);
    }
    
    /**
     * Enable/Disable an alert definition. For internal use only where the mtime
     * does not need to be reset on each update.
     *
     * @return <code>true</code> if the enable/disable succeeded.
     * @ejb:interface-method
     */
    public boolean updateAlertDefinitionInternalEnable(AuthzSubject subj,
                                                       List ids,
                                                       boolean enable)
        throws PermissionException {

        AlertDefinitionDAO dao = getAlertDefDAO();
        List triggerDefIds = new ArrayList(ids.size());
        
        for (Iterator it = ids.iterator(); it.hasNext(); ) {
            AlertDefinition def = dao.get((Integer) it.next());
            
            if (def != null && def.isEnabled() != enable) {
                // ...check that user has modify permission on alert definition's resource...
                canModifyAlertDefinition(subj, def.getAppdefEntityId());

                def.setEnabledStatus(enable);
                triggerDefIds.add(def.getId());
            }
        }
        
        if (!triggerDefIds.isEmpty()) {
            // HQ-1799: enable the triggers in batch to improve performance
            registeredTriggerManager.setAlertDefinitionTriggersEnabled(triggerDefIds, enable);
            return true;
        } else {
            return false;
        }
    }    

    /**
     * Set the escalation on the alert definition
     *
     * @ejb:interface-method
     */
    public void setEscalation(AuthzSubject subj, Integer defId, Integer escId)
        throws PermissionException
    {
        AlertDefinition def = getAlertDefDAO().findById(defId);
        
        // ...check that user has modify permission on alert definition's resource...
        canModifyAlertDefinition(subj, def.getAppdefEntityId());

        EscalationManagerLocal escMan = EscalationManagerEJBImpl.getOne();
        Escalation esc = escMan.findById(escId);

        // End any escalation we were previously doing.
        escMan.endEscalation(def);

        def.setEscalation(esc);
        def.setMtime(System.currentTimeMillis());

        // End all children's escalation
        for (Iterator it = def.getChildren().iterator(); it.hasNext(); ) {
            AlertDefinition child = (AlertDefinition) it.next();
            escMan.endEscalation(child);
        }

        getAlertDefDAO().setChildrenEscalation(def, esc);
    }

    /**
     * Returns the {@link AlertDefinition}s using the passed escalation.
     * @ejb:interface-method
     */
    public Collection getUsing(Escalation e) {
        return getAlertDefDAO().getUsing(e);
    }


    /**
     * Remove alert definitions
     * @ejb:interface-method
     */
    public void deleteAlertDefinitions(AuthzSubject subj, Integer[] ids)
        throws RemoveException, PermissionException
    {
        for (int i = 0; i < ids.length; i++) {
            AlertDefinition alertDefinition = getAlertDefDAO().findById(ids[i]);

            // Don't delete child alert definitions
            if (alertDefinition.getParent() != null &&
                !EventConstants.TYPE_ALERT_DEF_ID
                    .equals(alertDefinition.getParent().getId())) {
                continue;
            }
            
            // ...check that user has delete permission on alert definitions...
            canDeleteAlertDefinition(subj, alertDefinition.getAppdefEntityId());

            AlertAudit.deleteAlert(alertDefinition, subj);

            deleteAlertDefinition(subj, alertDefinition, false);
        }
    }



    /**
     * Set Resource to null on entity's alert definitions
     * @ejb:interface-method
     */
    public void disassociateResource(Resource r) {
        AlertDefinitionDAO aDao = getAlertDefDAO();
        List adefs = aDao.findAllByResource(r);

        for (Iterator i = adefs.iterator(); i.hasNext(); ) {
            AlertDefinition alertdef = (AlertDefinition) i.next();
            alertdef.setResource(null);
            alertdef.setDeleted(true);
        }
        aDao.getSession().flush();
    }
    
    /**
     * Prefetches all collections associated with each alertDef that is deleted and has a
     * null resourceId into ehcache.
     * @return {@link List} of {@link Integer} of {@link AlertDefintion} ids
     * @ejb:interface-method
     */
    public List getAllDeletedAlertDefs() {
        return getAlertDefDAO().findAndPrefetchAllDeletedAlertDefs();
    }

    /**
     * @param alertDefIds {@link List} of {@link Integer} of alertDefIds
     * @ejb:interface-method
     */
    public void cleanupAlertDefs(List alertDefIds) {
        if (alertDefIds.size() <= 0) {
            return;
        }
        final StopWatch watch = new StopWatch();
        final boolean debug = log.isDebugEnabled();
        final AlertDAO dao = getAlertDAO();
        final AlertDefinitionDAO aDao = getAlertDefDAO();
        final ActionDAO actionDAO = getActionDAO();
        int i=0;
        try {
            final List alertDefs = new ArrayList(alertDefIds.size());
            for (final Iterator it = alertDefIds.iterator(); it.hasNext();) {
                final Integer alertdefId = (Integer) it.next();
                if (debug) watch.markTimeBegin("findById");
                final AlertDefinition alertdef = aDao.findById(alertdefId);
                if (debug) watch.markTimeEnd("findById");
                alertDefs.add(alertdef);
            }
            // Delete the alerts
            if (debug) watch.markTimeBegin("deleteByAlertDefinition");
            dao.deleteByAlertDefinitions(alertDefs);
            if (debug) watch.markTimeEnd("deleteByAlertDefinition");

            if (debug) watch.markTimeBegin("loop");
            for (final Iterator it = alertDefs.iterator(); it.hasNext();) {
                final AlertDefinition alertdef = (AlertDefinition) it.next();

                // Remove the conditions
                if (debug) watch.markTimeBegin("remove conditions and triggers");
                alertdef.clearConditions();
                alertdef.getTriggersBag().clear();
                if (debug) watch.markTimeEnd("remove conditions and triggers");

                // Remove the actions
                if (debug) watch.markTimeBegin("removeActions");
                actionDAO.removeActions(alertdef);
                if (debug) watch.markTimeEnd("removeActions");

                if (debug) watch.markTimeBegin("remove from parent");
                if (alertdef.getParent() != null) {
                    alertdef.getParent().getChildrenBag().remove(alertdef);
                }
                if (debug) watch.markTimeEnd("remove from parent");

                // Actually remove the definition
                if (debug) watch.markTimeBegin("remove");
                aDao.remove(alertdef);
                if (debug) watch.markTimeEnd("remove");
                i++;
            }
            if (debug) watch.markTimeEnd("loop");

        } finally {
            if (debug) log.debug("deleted " + i + " alertDefs: " + watch);
        }
    }

    /** Find an alert definition and return a value object
     * @throws PermissionException if user does not have permission to manage
     * alerts
     * @ejb:interface-method
     */
    public AlertDefinitionValue getById(AuthzSubject subj, Integer id)
        throws PermissionException
    {
        AlertDefinitionValue adv = null;
        AlertDefinition ad = getByIdAndCheck(subj, id);
        if (ad != null) {
            adv = ad.getAlertDefinitionValue();
        }
        return adv;
    }

    /** Find an alert definition
     * @throws PermissionException if user does not have permission to manage
     * alerts
     * @ejb:interface-method
     */
    public AlertDefinition getByIdAndCheck(AuthzSubject subj, Integer id)
    throws PermissionException {
        AlertDefinition ad = getAlertDefDAO().get(id);
        if (ad != null) {
            if (ad.isDeleted()) {
                ad = null;
            } else {
                Resource r = ad.getResource();
                if (r == null || r.isInAsyncDeleteState()) {
                    ad = null;
                }
            }

            if (ad != null) {
                // ...check that user has view permission on alert definitions...
                canViewAlertDefinition(subj, ad.getAppdefEntityId());
            }
        }
        
        return ad;
    }

    /** Find an alert definition and return a basic value.  This is called by
     * the abstract trigger, so it does no permission checking.
     *
     * @param id The alert def Id.
     * @ejb:interface-method
     */
    public AlertDefinition getByIdNoCheck(Integer id) {
        return getAlertDefDAO().get(id);
    }

    /**
     * Check if an alert definition is a resource type alert definition.
     *
     * @param id The alert def Id.
     * @return <code>true</code> if the alert definition is a resource type
     *         alert definition.
     * @throws FinderException
     * @ejb:interface-method
     */
    public boolean isResourceTypeAlertDefinition(Integer id) {
        AlertDefinition ad = getAlertDefDAO().get(id);
        return ad.isResourceTypeDefinition();
    }



    /**
     * @ejb:interface-method
     */
    public AlertDefinition findAlertDefinitionById(Integer id) {
        return getAlertDefDAO().findById(id);
    }

    /** Get an alert definition's name
     * @ejb:interface-method
     */
    public String getNameById(Integer id)
        throws FinderException
    {
        return getAlertDefDAO().get(id).getName();
    }

    /** Get an alert definition's conditions
     * @ejb:interface-method
     */
    public AlertConditionValue[] getConditionsById(Integer id)
        throws FinderException
    {
        AlertDefinition def = getAlertDefDAO().get(id);
        Collection conds = def.getConditions();
        AlertConditionValue[] condVals = new AlertConditionValue[conds.size()];

        Iterator it = conds.iterator();
        for (int i = 0; it.hasNext(); i++) {
            AlertCondition cond = (AlertCondition) it.next();
            condVals[i] = cond.getAlertConditionValue();
        }
        return condVals;
    }

    /** Get list of alert conditions for a resource or resource type
     * @ejb:interface-method
     */
    public boolean isAlertDefined(AppdefEntityID id, Integer parentId) {
        Resource res = findResource(id);
        return getAlertDefDAO().findChildAlertDef(res, parentId) != null;
    }

    /**
     * Get list of all alert conditions
     *
     * @return a PageList of {@link AlertDefinitionValue} objects
     * @ejb:interface-method
     */
    public PageList findAllAlertDefinitions(AuthzSubject subj) {
        List vals = new ArrayList();

        for (Iterator i = getAlertDefDAO().findAll().iterator(); i.hasNext();) {
            AlertDefinition a = (AlertDefinition) i.next();
            try {
                // Only return the alert definitions that user can see
                // ...check that user has view permission on alert definitions...
                canViewAlertDefinition(subj, a.getAppdefEntityId());
            } catch (PermissionException e) {
                continue;
            }
            
            vals.add(a.getAlertDefinitionValue());
        }
        return new PageList(vals, vals.size());
    }

    /**
     * Get the resource-specific alert definition ID by parent ID, allowing for
     * the query to return a stale copy of the alert definition (for efficiency
     * reasons).
     *
     * @param aeid The resource.
     * @param pid The ID of the resource type alert definition (parent ID).
     * @param allowStale <code>true</code> to allow stale copies of an alert
     *                   definition in the query results; <code>false</code> to
     *                   never allow stale copies, potentially always forcing a
     *                   sync with the database.
     * @return The alert definition ID or <code>null</code> if no alert definition
     *         is found for the resource.
     * @ejb:interface-method
     */
    public Integer findChildAlertDefinitionId(AppdefEntityID aeid,
                                              Integer pid,
                                              boolean allowStale) {
        Resource res = findResource(aeid);
        AlertDefinition def = getAlertDefDAO().findChildAlertDef(res, pid, true);

        return def == null ? null : def.getId();
    }


    /**
     * Find alert definitions passing the criteria.
     *
     * @param minSeverity  Specifies the minimum severity that the defs should
     *                     be set for
     * @param enabled      If non-null, specifies the nature of the returned
     *                     definitions (i.e. only return enabled or disabled
     *                     defs)
     * @param excludeTypeBased  If true, exclude any alert definitions
     *                          associated with a type-based def.
     * @param pInfo        Paging information.  The sort field must be a
     *                     value from {@link AlertDefSortField}
     *
     * @ejb:interface-method
     */
    public List findAlertDefinitions(AuthzSubject subj,
                                     AlertSeverity minSeverity, Boolean enabled,
                                     boolean excludeTypeBased, PageInfo pInfo)
    {
        return getAlertDefDAO().findDefinitions(subj, minSeverity, enabled,
                                                excludeTypeBased, pInfo);
    }

    /**
     * Get the list of type-based alert definitions.
     *
     * @param enabled If non-null, specifies the nature of the returned defs.
     * @param pInfo Paging information.  The sort field must be a value from
     *              {@link AlertDefSortField}
     * @ejb:interface-method
     */
    public List findTypeBasedDefinitions(AuthzSubject subj, Boolean enabled,
                                         PageInfo pInfo)
        throws PermissionException
    {
        if (!PermissionManagerFactory.getInstance()
                .hasAdminPermission(subj.getId())) {
            throw new PermissionException("Only administrators can do this");
        }
        return getAlertDefDAO().findTypeBased(enabled, pInfo);
    }

    /**
     * Get list of alert definition POJOs for a resource
     * @throws PermissionException if user cannot manage alerts for resource
     * @ejb:interface-method
     */
    public List findAlertDefinitions(AuthzSubject subject, AppdefEntityID id)
        throws PermissionException {
        // ...check that user has view permission on alert definitions...
        canViewAlertDefinition(subject, id);

        return getAlertDefDAO().findByResource(findResource(id));
    }

    /**
     * @ejb:interface-method
     */
    public PageList findAlertDefinitions(AuthzSubject subj, AppdefEntityID id,
                                         PageControl pc)
        throws PermissionException
    {
        // ...check that user has view permission on alert definitions...
        canViewAlertDefinition(subj, id);

        Resource resource = findResource(id);
        AlertDefinitionDAO aDao = getAlertDefDAO();
        List adefs;

        if (pc.getSortattribute() == SortAttribute.CTIME) {
            adefs = aDao.findByResourceSortByCtime(resource, !pc.isDescending());
        } else {
            adefs = aDao.findByResource(resource, !pc.isDescending());
        }

        return _valuePager.seek(adefs, pc.getPagenum(), pc.getPagesize());
    }

    /**
     * Get list of alert definitions for a resource type.
     * @ejb:interface-method
     */
    public List findAlertDefinitions(AuthzSubject subject, Resource prototype)
        throws PermissionException
    {
        AlertDefinitionDAO aDao = getAlertDefDAO();
        // TODO: Check admin permission?
        return aDao.findAllByResource(prototype);
    }

    /**
     * Get list of alert conditions for a resource or resource type
     * @ejb:interface-method
     */
    public PageList findAlertDefinitions(AuthzSubject subj,
                                         AppdefEntityTypeID aetid,
                                         PageControl pc)
        throws PermissionException
    {
        AlertDefinitionDAO aDao = getAlertDefDAO();

        Resource res =
            ResourceManagerEJBImpl.getOne().findResourcePrototype(aetid);
        Collection adefs;
        if (pc.getSortattribute() == SortAttribute.CTIME) {
            adefs =
                aDao.findByResourceSortByCtime(res, pc.isAscending());
        }
        else {
            adefs = aDao.findByResource(res, pc.isAscending());
        }

        return _valuePager.seek(adefs, pc.getPagenum(), pc.getPagesize());
    }

    /**
     * Get a list of all alert definitions for the resource and its descendents
     * @param subj the caller
     * @param res the root resource
     * @return a list of alert definitions
     * @ejb:interface-method
     */
    public List findRelatedAlertDefinitions(AuthzSubject subj, Resource res) {
        List defs = getAlertDefDAO().findByRootResource(subj, res);
        return defs;
    }

    /**
     * Get list of children alert definition for a parent alert definition
     * @ejb:interface-method
     */
    public PageList findAlertDefinitionChildren(Integer id) {
        AlertDefinition def = getAlertDefDAO().findById(id);

        PageControl pc = PageControl.PAGE_ALL;
        return _valuePager.seek(def.getChildren(), pc.getPagenum(),
                               pc.getPagesize());
    }

    /**
     * Get list of alert definition names for a resource
     * @ejb:interface-method
     */
    public SortedMap findAlertDefinitionNames(AuthzSubject subj,
                                              AppdefEntityID id,
                                              Integer parentId)
        throws PermissionException
    {
    	if(parentId == null) {
    	    // ...check that user has view permission on alert definitions...
            canViewAlertDefinition(subj, id);
    	}

    	return findAlertDefinitionNames(id, parentId);
    }

    /** 
     * Get list of alert definition names for a resource
     * @ejb:interface-method
     */
    public SortedMap findAlertDefinitionNames(AppdefEntityID id,
                                              Integer parentId)
    {
        AlertDefinitionDAO aDao = getAlertDefDAO();
        TreeMap ret = new TreeMap();
        Collection adefs;

        if (parentId != null) {
            if (EventConstants.TYPE_ALERT_DEF_ID.equals(parentId)) {
                AppdefEntityTypeID aetid = new AppdefEntityTypeID(id.getType(),
                                                                  id.getId());
                Resource res =
                    ResourceManagerEJBImpl.getOne().findResourcePrototype(aetid);
                adefs = aDao.findByResource(res);
            }
            else  {
                AlertDefinition def = getAlertDefDAO().findById(parentId);
                adefs = def.getChildren();
            }
        } else {
            Resource res = findResource(id);
            adefs = aDao.findByResource(res);
        }

        // Use name as key so that map is sorted
        for (Iterator i = adefs.iterator(); i.hasNext(); ) {
            AlertDefinition adLocal = (AlertDefinition) i.next();
            ret.put(adLocal.getName(), adLocal.getId());
        }
        return ret;
    }

    /**
     * Return array of two values: enabled and act on trigger ID
     * @ejb:interface-method
     */
    public boolean isEnabled(Integer id) {
        return getAlertDefDAO().isEnabled(id);
    }

    /**
     * @ejb:interface-method
     */
    public int getActiveCount() {
        return getAlertDefDAO().getNumActiveDefs();
    }

    /**
     * @ejb:interface-method
     */
    public void startup() {
        log.info("Alert Definition Manager starting up!");

        HQApp.getInstance().registerCallbackListener(AlertDefinitionChangeCallback.class,
                                                     new AlertDefinitionChangeCallback() {
            public void postCreate(AlertDefinition def) {
                removeFromCache(def);
            }

            public void postDelete(AlertDefinition def) {
                removeFromCache(def);
            }

            public void postUpdate(AlertDefinition def) {
                removeFromCache(def);
            }

            private void removeFromCache(AlertDefinition def) {
                AvailabilityDownAlertDefinitionCache cache =
                        AvailabilityDownAlertDefinitionCache.getInstance();

                synchronized (cache) {
                    cache.remove(def.getAppdefEntityId());

                    AlertDefinition childDef = null;
                    for (Iterator it=def.getChildren().iterator(); it.hasNext(); ) {
                        childDef = (AlertDefinition) it.next();
                        Resource r = childDef.getResource();
                        if (r == null || r.isInAsyncDeleteState()) {
                            continue;
                        }
                        cache.remove(childDef.getAppdefEntityId());
                    }
                }
            }
        });
    }

    public static AlertDefinitionManagerLocal getOne() {
        try {
            return AlertDefinitionManagerUtil.getLocalHome().create();
        } catch(Exception e) {
            throw new SystemException(e);
        }
    }

    /**
     * @ejb:create-method
     */
    public void ejbCreate() throws CreateException {
        try {
            _valuePager = Pager.getPager(VALUE_PROCESSOR);
        } catch ( Exception e ) {
            throw new CreateException("Could not create value pager:" + e);
        }
    }

    public void ejbPostCreate() {}
    public void ejbActivate() {}
    public void ejbPassivate() {}
    public void ejbRemove() {}
    public void setSessionContext(SessionContext ctx) {}
}
