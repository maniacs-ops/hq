/*
 * 'SNMPSession_v3.java' NOTE: This copyright does *not* cover user programs
 * that use HQ program services by normal system calls through the application
 * program interfaces provided as part of the Hyperic Plug-in Development Kit or
 * the Hyperic Client Development Kit - this is merely considered normal use of
 * the program, and does *not* fall under the heading of "derived work".
 * Copyright (C) [2004, 2005, 2006, 2007, 2008, 2009], Hyperic, Inc. This file
 * is part of HQ. HQ is free software; you can redistribute it and/or modify it
 * under the terms version 2 of the GNU General Public License as published by
 * the Free Software Foundation. This program is distributed in the hope that it
 * will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details. You should have received a copy of the GNU
 * General Public License along with this program; if not, write to the Free
 * Software Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307
 * USA.
 */

package org.hyperic.snmp;

import java.io.*; // stub

import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.UserTarget;
import org.snmp4j.log.Log4jLogFactory;
import org.snmp4j.log.LogFactory;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.AuthMD5;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.PrivAES192;
import org.snmp4j.security.PrivAES256;
import org.snmp4j.security.PrivDES;
import org.snmp4j.security.Priv3DES;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;

/**
 * Implements the SNMPSession interface for SNMPv3 sessions by extending the
 * SNMPSession_v2c implementation. SNMPv3 is only different from v1 or v2c in
 * the way that a session is initialized.
 */
class SNMPSession_v3
    extends SNMPSession_v2c
{
    static {
        USM usm = new USM(SecurityProtocols.getInstance(), new OctetString(MPv3.createLocalEngineID()), 0);

        SecurityModels.getInstance().addSecurityModel(usm);

        if ("true".equals(System.getProperty("snmpLogging"))) {
            LogFactory.setLogFactory(new Log4jLogFactory());
        }
    }

    SNMPSession_v3() {
        this.version = SnmpConstants.version3;
    }

    protected PDU newPDU() {
        ScopedPDU pdu = new ScopedPDU();

        return pdu;
    }

    private OctetString getAuthPassphrase(String val) {
        if (val == null || val.length() == 0) {
            return null;
        }

        return new OctetString(val);        
    }
    
    private OctetString getPrivPassphrase(String defVal) {
        String val = System.getProperty("snmpPrivacyPassPhrase", defVal);

        if (val == null || val.length() == 0) {
            return null;
        }

        return new OctetString(val);
    }

    private OID getPrivProtocol(String defVal) throws SNMPException {
        String val = System.getProperty("snmpPrivacyType", defVal);

        if (val == null
                || val.equalsIgnoreCase("none")
                || val.length() == 0) {
            return null;
        }

        if (val.equals("DES")) {
            return PrivDES.ID;
        } else if (val.equals("3DES")) {
            return Priv3DES.ID;
        } else if (val.equals("AES128") || val.equals("AES-128") || val.equals("AES")) {
            return PrivAES128.ID;
        } else if (val.equals("AES192") || val.equals("AES-192")) {
            return PrivAES192.ID;
        } else if (val.equals("AES256") || val.equals("AES-256")) {
            return PrivAES256.ID;
        } else {
            throw new SNMPException("Privacy protocol " + val + " not supported");
        }
    }

    private OID getAuthProtocol(String authMethod) {        
        if (authMethod == null 
                || authMethod.equalsIgnoreCase("none")
                || authMethod.length() == 0) {
            return null;
        } else if (authMethod.equalsIgnoreCase("md5")) {
            return AuthMD5.ID;
        } else if (authMethod.equalsIgnoreCase("sha")) {
            return AuthSHA.ID;
        } else {
            throw new IllegalArgumentException("unknown authentication protocol: " + authMethod);
        }
    }
    
    void init(String host, String port, String transport, String user, 
              String authType, String authPassword, 
              String privType, String privPassword) 
        throws SNMPException
    {                        
        OID authProtocol = getAuthProtocol(authType);
        OID privProtocol = getPrivProtocol(privType);

        OctetString securityName = new OctetString(user);
        OctetString authPassphrase = getAuthPassphrase(authPassword);
        OctetString privPassphrase = getPrivPassphrase(privPassword);

        UserTarget target = new UserTarget();

        target.setSecurityName(securityName);

        if (authPassphrase != null) {
            if (privPassphrase != null) {
                target.setSecurityLevel(SecurityLevel.AUTH_PRIV);
            } else {
                target.setSecurityLevel(SecurityLevel.AUTH_NOPRIV);
            }
        } else {
            target.setSecurityLevel(SecurityLevel.NOAUTH_NOPRIV);
        }

        this.target = target;

        initSession(host, port, transport);

        USM usm = this.session.getUSM();

        if (usm.getUserTable().getUser(securityName) != null) {
            return;
        }

        usm.addUser(securityName, new UsmUser(securityName, authProtocol, authPassphrase, privProtocol, privPassphrase));
    }
}
