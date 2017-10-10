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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.Principal;
import javax.mail.MessagingException;
import java.text.SimpleDateFormat;
import java.util.*;
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
    private String mailTemplateFilePath = null;
    private String mailTemplate = null;

    private final static int STATE_BEGIN = 1;
    private Map<String, String> options;
    private ResourceBundle bundle;
    private Map<String, String> sharedState;
    private AMSendMail amMail;
    private AMIdentity identity = null;

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
        mailTemplateFilePath = CollectionHelper.getMapAttr(
                options, "devicewarningmail-template", "");
        try {
            mailTemplate = new String(Files.readAllBytes(Paths.get(mailTemplateFilePath)));
        } catch (IOException e) {
            debug.error(e.getMessage());
        }
        mailSubject = CollectionHelper.getMapAttr(
                options, "devicewarningmail-subject", "");
        mailFrom = CollectionHelper.getMapAttr(
                options, "devicewarningmail-from", "");
        mailAttribute = CollectionHelper.getMapAttr(
                options, "devicewarningmail-mail-attribute", "");
        userAgentInfo = new UserAgentInfo(getHttpServletRequest());
    }

    @Override
    public int process(Callback[] callbacks, int state) throws LoginException {
        switch (state) {
            case STATE_BEGIN:
                if (mailTemplate == null) {
                    debug.error("Could not read mail template file");
                    throw new AuthLoginException("Could not send warning mail to user");
                }

                // Retrieve identity object of the user
                identity = getIdentity();
                if (identity == null) {
                    debug.error("Could not retrieve identity");
                    throw new AuthLoginException("Could not send warning mail to user");
                }

                String body = replaceVariables(mailTemplate);
                String subject = replaceVariables(mailSubject);
                try {
                    // Retrieve user mail attribute
                    Set<String> mails = identity.getAttribute(mailAttribute);
                    if (mails == null || mails.isEmpty()) {
                        debug.error("Could not retrieve user mail from attribute : " + mailAttribute);
                        throw new AuthLoginException("Could not send warning mail to user");
                    }

                    // Send the mail
                    amMail.postMail(mails.toArray(new String[0]), subject, body, mailFrom, "text/html", "UTF-8");
                    return ISAuthConstants.LOGIN_SUCCEED;
                } catch (IdRepoException | SSOException | MessagingException e) {
                    debug.error(e.getMessage());
                }
                throw new AuthLoginException("Could not send warning mail to user");
            default:
                throw new AuthLoginException("invalid state");
        }
    }

    private String replaceVariables(String content) {
        String regex = "%\\{([^}]*)\\}";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(content);
        String attribute = null;
        Set<String> attrValues = null;
        while (matcher.find()) {
            attribute = matcher.group(1);
            attribute = attribute.trim();
            if (attribute.equals("browser")) {
                content = content.replace(matcher.group(), userAgentInfo.getBrowser());
            } else if (attribute.equals("device")) {
                content = content.replace(matcher.group(), userAgentInfo.getDevice());
            } else if (attribute.equals("date")) {
                String date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                content = content.replace(matcher.group(), date);
            } else {
                try {
                    attrValues = identity.getAttribute(attribute);
                    if (!attrValues.isEmpty()) {
                        content = content.replace(matcher.group(), StringUtils.join(attrValues, "<br />"));
                    }
                } catch (IdRepoException | SSOException e) {
                    debug.error(e.getMessage());
                }
            }
        }
        return content;
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
