/**
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2011-2015 ForgeRock AS. All Rights Reserved
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt.
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file at legal/CDDLv1.0.txt.
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 */

package com.mirzlab.openam;

import java.security.Principal;
import javax.mail.MessagingException;
import java.util.Collections;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.login.LoginException;

import com.iplanet.am.util.AMSendMail;
import com.iplanet.sso.SSOException;
import com.sun.identity.authentication.spi.AMLoginModule;
import com.sun.identity.authentication.spi.AuthLoginException;
import com.sun.identity.authentication.util.ISAuthConstants;
import com.sun.identity.idm.*;
import com.sun.identity.shared.datastruct.CollectionHelper;
import com.sun.identity.shared.debug.Debug;
import org.apache.commons.lang.StringUtils;

public class DeviceWarningMailModule extends AMLoginModule {

    private final static String DEBUG_NAME = "DeviceWarningMailModule";
    private final static Debug debug = Debug.getInstance(DEBUG_NAME);

    private final static String amAuthDeviceWarningMailModule = "amAuthDeviceWarningMailModule";

    private String userName = null;
    private String mailSubject = null;
    private String mailAttribute = null;
    private String mailFrom = null;
    private String mailTemplate = null;

    private final static int STATE_BEGIN = 1;
    private Map<String, String> options;
    private ResourceBundle bundle;
    private Map<String, String> sharedState;
    private AMSendMail amMail;

    // Thank you https://gist.github.com/bryanjswift/318237
    private UserAgentInfo userAgentInfo;

    public DeviceWarningMailModule() {
        super();
    }

    @Override
    public void init(Subject subject, Map sharedState, Map options) {
        this.options = options;
        this.sharedState = sharedState;
        this.bundle = amCache.getResBundle(amAuthDeviceWarningMailModule, getLoginLocale());
        this.amMail = new AMSendMail();

        // Retrieve user id from previous module
        userName = (String) sharedState.get(getUserKey());
        mailTemplate = CollectionHelper.getMapAttr(
                options, "devicewarningmail-template", "");
        mailSubject = CollectionHelper.getMapAttr(
                options, "devicewarningmail-subject", "");
        mailFrom = CollectionHelper.getMapAttr(
                options, "devicewarningmail-from", "");
        mailAttribute = CollectionHelper.getMapAttr(
                options, "devicewarningmail-mailAttribute", "");
        userAgentInfo = new UserAgentInfo(getHttpServletRequest());
    }

    @Override
    public int process(Callback[] callbacks, int state) throws LoginException {
        switch (state) {
            case STATE_BEGIN:
                AMIdentity identity = getIdentity();
                if (identity == null) {
                    debug.error("Could not retrieve identity");
                    throw new AuthLoginException("Could not send warning mail to user");
                }

                String mailBody = mailTemplate;
                String regex = "%\\{([^}]*)\\}";
                Pattern pattern = Pattern.compile(regex);
                Matcher matcher = pattern.matcher(mailBody);
                String attribute = null;
                Set<String> attrValues = null;
                try {
                    while (matcher.find()) {
                        attribute = matcher.group(1);
                        attrValues = identity.getAttribute(attribute);
                        mailBody = mailBody.replace(matcher.group(), StringUtils.join(attrValues, "<br />"));
                    }
                    Set<String> mails = identity.getAttribute(mailAttribute);

                    String subject = mailSubject.replace("%{browser}", userAgentInfo.getBrowser());
                    subject = subject.replace("%{device}", userAgentInfo.getDevice());
                    amMail.postMail((String[]) mails.toArray(), subject, mailBody, mailFrom, "text/html", "UTF-8");
                    return ISAuthConstants.LOGIN_SUCCEED;
                } catch (IdRepoException | SSOException | MessagingException e) {
                    debug.error(e.getMessage());
                }
                throw new AuthLoginException("Could not send warning mail to user");
            default:
                throw new AuthLoginException("invalid state");
        }
    }

   private AMIdentity getIdentity() {
        AMIdentity amIdentity = null;
        AMIdentityRepository amIdRepo = getAMIdentityRepository(getRequestOrg());

        IdSearchControl idsc = new IdSearchControl();
        idsc.setAllReturnAttributes(true);
        Set<AMIdentity> results = Collections.EMPTY_SET;

        try {
            idsc.setMaxResults(0);
            IdSearchResults searchResults = amIdRepo.searchIdentities(IdType.USER, userName, idsc);
            if (searchResults != null) {
                results = searchResults.getSearchResults();
            }

            if (results.isEmpty()) {
                debug.error("User " + userName + " is not found");
            } else if (results.size() > 1) {
                debug.error("More than one user found for the userName " + userName);
            } else {
                amIdentity = results.iterator().next();
            }

        } catch (SSOException | IdRepoException e) {
            debug.error(e.getMessage());
        }

        return amIdentity;
    }

    @Override
    public Principal getPrincipal() {
        return new DeviceWarningMailModulePrincipal(userName);
    }
}
