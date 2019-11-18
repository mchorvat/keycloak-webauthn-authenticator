/*
 * Copyright 2002-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.authentication.authenticators.browser;

import com.webauthn4j.data.WebAuthnAuthenticationContext;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.data.client.challenge.Challenge;
import com.webauthn4j.data.client.challenge.DefaultChallenge;
import com.webauthn4j.server.ServerProperty;

import org.jboss.logging.Logger;
import org.keycloak.WebAuthnConstants;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.AuthenticationFlowException;
import org.keycloak.authentication.Authenticator;
import org.keycloak.common.util.Base64Url;
import org.keycloak.common.util.UriUtils;
import org.keycloak.credential.WebAuthnCredentialModel;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.forms.login.freemarker.model.WebAuthnAuthenticatorsBean;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;

import javax.ws.rs.core.MultivaluedMap;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

public class WebAuthn4jAuthenticator implements Authenticator {

    private static final Logger logger = Logger.getLogger(WebAuthn4jAuthenticator.class);

    private KeycloakSession session;

    public WebAuthn4jAuthenticator(KeycloakSession session) {
        this.session = session;
    }

    private Map<String, String> generateParameters(RealmModel realm, URI baseUri) {
        Map<String, String> params = new HashMap<>();
        Challenge challenge = new DefaultChallenge();
        params.put(WebAuthnConstants.CHALLENGE, Base64Url.encode(challenge.getValue()));
        params.put(WebAuthnConstants.RPID, baseUri.getHost());
        params.put(WebAuthnConstants.ORIGIN, UriUtils.getOrigin(baseUri));
        return params;
    }

    public void authenticate(AuthenticationFlowContext context) {
        LoginFormsProvider form = context.form();
        Map<String, String> params = generateParameters(context.getRealm(), context.getUriInfo().getBaseUri());
        context.getAuthenticationSession().setAuthNote(WebAuthnConstants.AUTH_CHALLENGE_NOTE, params.get(WebAuthnConstants.CHALLENGE));
        UserModel user = context.getUser();
        boolean isUserIdentified = false;
        if (user != null) {
            // in 2 Factor Scenario where the user has already identified
            isUserIdentified = true;
            form.setAttribute("authenticators", new WebAuthnAuthenticatorsBean(user));
        } else {
            // in ID-less & Password-less Scenario
            // NOP
        }
        params.put("isUserIdentified", Boolean.toString(isUserIdentified));
        params.forEach(form::setAttribute);
        context.challenge(form.createForm("webauthn.ftl"));
    }

    public void action(AuthenticationFlowContext context) {
        MultivaluedMap<String, String> params = context.getHttpRequest().getDecodedFormParameters();

        // receive error from navigator.credentials.get()
        String error = params.getFirst(WebAuthnConstants.ERROR);
        if (error != null && !error.isEmpty()) {
            throw new AuthenticationFlowException("exception raised from navigator.credentials.get() : " + error, AuthenticationFlowError.INVALID_USER);
        }

        String baseUrl = UriUtils.getOrigin(context.getUriInfo().getBaseUri());
        String rpId = context.getUriInfo().getBaseUri().getHost();

        Origin origin = new Origin(baseUrl);
        Challenge challenge = new DefaultChallenge(context.getAuthenticationSession().getAuthNote(WebAuthnConstants.AUTH_CHALLENGE_NOTE));
        ServerProperty server = new ServerProperty(origin, rpId, challenge, null);

        byte[] credentialId = Base64Url.decode(params.getFirst(WebAuthnConstants.CREDENTIAL_ID));
        byte[] clientDataJSON = Base64Url.decode(params.getFirst(WebAuthnConstants.CLIENT_DATA_JSON));
        byte[] authenticatorData = Base64Url.decode(params.getFirst(WebAuthnConstants.AUTHENTICATOR_DATA));
        byte[] signature = Base64Url.decode(params.getFirst(WebAuthnConstants.SIGNATURE));

        String userId = params.getFirst(WebAuthnConstants.USER_HANDLE);
        boolean isUVFlagChecked = true;
        logger.debugv("userId = {0}", userId);

        if (userId == null || userId.isEmpty()) {
            // in 2 Factor with Resident Key not supported Authenticator Scenario
            userId = context.getUser().getId();
            isUVFlagChecked = false;
        } else {
            if (context.getUser() != null) {
                // in 2 Factor with Resident Key supported Authenticator Scenario
                String firstAuthenticatedUserId = context.getUser().getId();
                logger.debugv("firstAuthenticatedUserId = {0}", firstAuthenticatedUserId);
                if (firstAuthenticatedUserId != null && !firstAuthenticatedUserId.equals(userId)) {
                    throw new AuthenticationFlowException("First authenticated user is not the one authenticated by 2nd factor authenticator", AuthenticationFlowError.USER_CONFLICT);
                }
            } else {
                // in Passwordless with Resident Key supported Authenticator Scenario
                // NOP
            }
        }
        UserModel user = session.users().getUserById(userId, context.getRealm());
        WebAuthnAuthenticationContext authenticationContext = new WebAuthnAuthenticationContext(
                credentialId,
                clientDataJSON,
                authenticatorData,
                signature,
                server,
                isUVFlagChecked
        );

        WebAuthnCredentialModel cred = new WebAuthnCredentialModel();
        cred.setAuthenticationContext(authenticationContext);

        boolean result = false;
        try {
            result = session.userCredentialManager().isValid(context.getRealm(), user, cred);
        } catch (Exception e) {
            e.printStackTrace();
            throw new AuthenticationFlowException("unknown user authenticated by the authenticator", AuthenticationFlowError.UNKNOWN_USER);
        }
        if (result) {
            context.setUser(user);
            context.success();
        } else {
            context.cancelLogin();
        }
    }

    public boolean requiresUser() {
        return false;
    }

    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
    }

    public void close() {

    }
}
